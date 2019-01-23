/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.shamrock.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.JarURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.shamrock.dev.DevModeMain;
import org.jboss.shamrock.maven.utilities.MojoUtils;

/**
 * The dev mojo, that runs a shamrock app in a forked process
 * <p>
 */
@Mojo(name = "dev", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class DevMojo extends AbstractMojo {

    private static final String RESOURCES_PROP = "shamrock.undertow.resources";

    /**
     * The directory for compiled classes.
     */
    @Parameter(readonly = true, required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${fakereplace}")
    private boolean fakereplace = false;

    /**
     * If this server should be started in debug mode. The default is to start in debug mode without suspending and listen on
     * port 5005. It supports the following options:
     * <table>
     * <tr>
     * <td><b>Value</b></td><td>Effect</td>
     * </tr>
     * <tr>
     * <td><b>false</b></td><td>The JVM is not started in debug mode</td>
     * </tr>
     * <tr>
     * <td><b>true</b></td><td>The JVM is started in debug mode and suspends until a debugger is attached to port 5005</td>
     * </tr>
     * <tr>
     * <td><b>client</b></td><td>The JVM is started in client mode, and attempts to connect to localhost:5005</td>
     * </tr>
     * <tr>
     * <td><b>{port}</b></td><td>The JVM is started in debug mode and suspends until a debugger is attached to {port}</td>
     * </tr>
     * </table>
     */
    @Parameter(defaultValue = "${debug}")
    private String debug;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDir;

    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File sourceDir;

    @Parameter(defaultValue = "${jvm.args}")
    private String jvmArgs;

    /**
     * This value is intended to be set to true when some generated bytecode
     * is erroneous causing the JVM to crash when the verify:none option is set (which is on by default)
     */
    @Parameter(defaultValue = "${preventnoverify}")
    private boolean preventnoverify = false;


    @Override
    public void execute() throws MojoFailureException {

        boolean found = false;
        for(Plugin i : project.getBuildPlugins()) {
            if(i.getGroupId().equals(MojoUtils.getPluginGroupId())
                    && i.getArtifactId().equals(MojoUtils.getPluginArtifactId())) {
                for(PluginExecution p : i.getExecutions()) {
                    if(p.getGoals().contains("build")) {
                        found = true;
                        break;
                    }
                }
            }
        }
        if(!found) {
            getLog().warn("The shamrock-maven-plugin build goal was not configured for this project, " +
                    "skipping shamrock:dev as this is assumed to be a support library. If you want to run shamrock dev" +
                    " on this project make sure the shamrock-maven-plugin is configured with a build goal.");
            return;
        }

        if (! sourceDir.isDirectory()) {
            throw new MojoFailureException("The `src/main/java` directory is required, please create it.");
        }

        if (! buildDir.isDirectory()  || ! new File(buildDir, "classes").isDirectory()) {
            throw new MojoFailureException("The project has no output yet, run `mvn compile shamrock:dev`.");
        }

        try {
            List<String> args = new ArrayList<>();
            args.add("java");
            if (debug == null) {
                // debug mode not specified
                // make sure 5005 is not used, we don't want to just fail if something else is using it
                try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 5005)) {
                    getLog().error("Port 5005 in use, not starting in debug mode");
                } catch (IOException e) {
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=n");
                }
            } else if (debug.toLowerCase().equals("client")) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=localhost:5005,server=n,suspend=n");
            } else if (debug.toLowerCase().equals("true")) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=localhost:5005,server=y,suspend=y");
            } else if (!debug.toLowerCase().equals("false")) {
                try {
                    int port = Integer.parseInt(debug);
                    if (port <= 0) {
                        throw new MojoFailureException("The specified debug port must be greater than 0");
                    }
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=" + port + ",server=y,suspend=y");
                } catch (NumberFormatException e) {
                    throw new MojoFailureException(
                            "Invalid value for debug parameter: " + debug + " must be true|false|client|{port}");
                }
            }
            if (jvmArgs != null) {
                args.addAll(Arrays.asList(jvmArgs.split(" ")));
            }

            for (Resource r : project.getBuild().getResources()) {
                File f = new File(r.getDirectory());
                File servletRes = new File(f, "META-INF/resources");
                if (servletRes.exists()) {
                    args.add("-D" + RESOURCES_PROP + "=" + servletRes.getAbsolutePath());
                    getLog().info("Using servlet resources " + servletRes.getAbsolutePath());
                    break;
                }
            }

            // the following flags reduce startup time and are acceptable only for dev purposes
            args.add("-XX:TieredStopAtLevel=1");
            if(!preventnoverify) {
                args.add("-Xverify:none");
            }

            //build a class-path string for the base platform
            //this stuff does not change
            StringBuilder classPath = new StringBuilder();
            for (Artifact artifact : project.getArtifacts()) {
                classPath.append(artifact.getFile().toPath().toAbsolutePath().toUri().toURL().toString());
                classPath.append(" ");
            }
            args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
            File wiringClassesDirectory = new File(buildDir, "wiring-classes");
            wiringClassesDirectory.mkdirs();

            classPath.append(wiringClassesDirectory.toPath().toAbsolutePath().toUri().toURL().toString()).append("/");
            classPath.append(' ');

            if (fakereplace) {
                File target = new File(buildDir, "fakereplace.jar");
                if (!target.exists()) {
                    //this is super yuck, but there does not seen to be an easy way
                    //to get dependency artifacts. Fakereplace must be called fakereplace.jar to work
                    //so we copy it to the target directory
                    URL resource = getClass().getClassLoader().getResource("org/fakereplace/core/Fakereplace.class");
                    if (resource == null) {
                        throw new RuntimeException("Could not determine Fakereplace location");
                    }
                    String filePath = resource.getPath();
                    try (FileInputStream in = new FileInputStream(filePath.substring(5, filePath.lastIndexOf('!')))) {
                        try (FileOutputStream out = new FileOutputStream(target)) {
                            byte[] buffer = new byte[1024];
                            int r;
                            while ((r = in.read(buffer)) > 0) {
                                out.write(buffer, 0, r);
                            }
                        }
                    }
                }
                args.add("-javaagent:" + target.getAbsolutePath());
                args.add("-Dshamrock.fakereplace=true");
            }

            //we also want to add the maven plugin jar to the class path
            //this allows us to just directly use classes, without messing around copying them
            //to the runner jar
            URL classFile = DevModeMain.class.getClassLoader().getResource(DevModeMain.class.getName().replace('.', File.separatorChar) + ".class");
            Path path;
            if (classFile.getProtocol().equals("jar")) {
                String jarPath = classFile.getPath().substring(0, classFile.getPath().lastIndexOf('!'));
                path = Paths.get(new URI(jarPath));
            } else if (classFile.getProtocol().equals("file")) {
                String filePath = classFile.getPath().substring(0, classFile.getPath().lastIndexOf(DevModeMain.class.getName().replace('.', '/')));
                path = Paths.get(new URI(classFile.getProtocol(), classFile.getHost(), filePath, null));
            } else {
                throw new MojoFailureException("Unsupported DevModeMain artifact URL:" + classFile);
            }
            classPath.append(path.toAbsolutePath().toUri().toURL().toString());
            if (classFile.getProtocol().equals("file")) {
                classPath.append('/');
            }

            //now we need to build a temporary jar to actually run

            File tempFile = new File(buildDir, project.getArtifactId()+"-dev.jar");
            tempFile.delete();
            tempFile.deleteOnExit();

            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tempFile))) {
                out.putNextEntry(new ZipEntry("META-INF/"));
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);
            }
            String resources = null;
            for(Resource i : project.getBuild().getResources()) {
                //todo: support multiple resources dirs for config hot deployment
                resources = i.getDirectory();
                break;
            }

            outputDirectory.mkdirs();

            args.add("-Dshamrock.runner.classes=" + outputDirectory.getAbsolutePath());
            args.add("-Dshamrock.runner.sources=" + sourceDir.getAbsolutePath());
            if(resources != null) {
                args.add("-Dshamrock.runner.resources=" + new File(resources).getAbsolutePath());
            }
            args.add("-jar");
            args.add(tempFile.getAbsolutePath());
            args.add(outputDirectory.getAbsolutePath());
            args.add(wiringClassesDirectory.getAbsolutePath());
            args.add(new File(buildDir, "transformer-cache").getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(args.toArray(new String[0]));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.directory(outputDirectory);
            Process p = pb.start();

            int val = p.waitFor();
        } catch (Exception e) {
            throw new MojoFailureException("Failed to run", e);
        }
    }

}
