package co.elastic.cloud.gradle.docker;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.CopySpec;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@CacheableTask
public class DockerBuildTask extends org.gradle.api.DefaultTask {

    private DockerBuildExtension extension;
    
    private final File dockerSave;

    public DockerBuildTask() {
        dockerSave = new File(getProject().getBuildDir(), "docker/docker.img.tar");
    }

    @Nested
    public DockerBuildExtension getExtension() {
        return extension;
    }

    public void setExtension(DockerBuildExtension extension) {
        this.extension = extension;
    }

    @OutputFile
    public File getDockerSave() {
        return dockerSave;
    }

    @TaskAction
    public void doBuildDockerImage() throws IOException {
        if (!this.extension.getWorkingDir().isDirectory()) {
            throw new GradleException("Can't build docker image, missing working directory " + extension.getWorkingDir());
        }
        Path dockerfile = extension.getWorkingDir().toPath().getParent().resolve("Dockerfile");
        generateDockerFile(dockerfile);

        ExecOperations execOperations = getExecOperations();
        String tag = DockerBuildPlugin.getTag(getProject());
        ExecResult imageBuild = execOperations.exec(spec -> {
            spec.setWorkingDir(dockerfile.getParent());
            // Remain independent from the host environment
            spec.setEnvironment(Collections.emptyMap());
            // We build with --no-cache to make things more straight forward, since we already cache images using Gradle's build cache
            spec.setCommandLine(
                    "docker" , "image", "build", "--progress=plain", "--no-cache", "--tag=" + tag, "."
            );
            spec.setIgnoreExitValue(true);
        });
        if (imageBuild.getExitValue() != 0) {
            throw new GradleException("Failed to build docker image, see the docker build log in the task output");
        }
        ExecResult imageSave = execOperations.exec(spec -> {
            spec.setWorkingDir(dockerSave.getParent());
            spec.setEnvironment(Collections.emptyMap());
            spec.setCommandLine("docker", "save", "--output=" + dockerSave.getName(), tag);
            spec.setIgnoreExitValue(true);
        });
        if (imageSave.getExitValue() != 0) {
            throw new GradleException("Failed to save docker image, see the docker build log in the task output");
        }
    }

    @Inject
    public ExecOperations getExecOperations() {
        throw new IllegalStateException("not implemented");
    }

    private void generateDockerFile(Path targetFile) throws IOException {
        if (Files.exists(targetFile)) {
            Files.delete(targetFile);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile)) {
            writer.write("#############################\n");
            writer.write("#                           #\n");
            writer.write("# Auto generated Dockerfile #\n");
            writer.write("#                           #\n");
            writer.write("#############################\n\n");
            if (!extension.getFromProject().isPresent()) {
                writer.write("FROM " + extension.getFrom() + "\n");
            } else {
                Project otherProject = extension.getFromProject().get();
                DockerBuildExtension otherExtension = otherProject.getExtensions().getByType(DockerBuildExtension.class);
                writer.write("# " + otherProject.getPath() + " (a.k.a " + DockerBuildPlugin.getTag(otherProject) + ")\n");
                writer.write("FROM " + otherExtension.getBuiltImageHash() + "\n\n");
            }

            if (extension.getMaintainer() != null) {
                writer.write("MAINTAINER " + extension.getMaintainer() + "\n\n");
            }

            writer.write("# FS hierarchy is set up in Gradle, so we just copy it in\n");
            writer.write("# COPY and RUN commands are kept consistent with the DSL\n");

            extension.forEachCopyAndRunLayer(
                    (ordinal, commands) -> {
                        try {
                            writer.write("RUN " + String.join(" && \\\n    ", commands) + "\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    (ordinal, copySpecAction) -> {
                        try {
                            writer.write("COPY " + extension.getWorkingDir().getName() + "/" + "layer" + ordinal + " /\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
            );
            writer.write("\n");

            if (getExtension().getEntryPoint() != null) {
                writer.write("ENTRYPOINT " + extension.getEntryPoint() + "\n\n");
            }
            if (getExtension().getCmd() != null) {
                writer.write("CMD " + extension.getEntryPoint() + "\n\n");
            }

            for (Map.Entry<String, String> entry : extension.getLabel().entrySet()) {
                writer.write("LABEL " + entry.getKey() + "=" + entry.getValue() + "\n");
            }
            if (!extension.getLabel().isEmpty()) {
                writer.write("\n");
            }

            for (Map.Entry<String, String> entry : extension.getEnv().entrySet()) {
                writer.write("ENV " + entry.getKey() + "=" + entry.getValue() + "\n");
            }
            if (!extension.getEnv().isEmpty()) {
                writer.write("\n");
            }

        }
    }
}
