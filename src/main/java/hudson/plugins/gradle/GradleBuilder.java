package hudson.plugins.gradle;

import hudson.*;
import hudson.model.*;
import hudson.remoting.Callable;
import hudson.slaves.NodeSpecific;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import hudson.util.ArgumentListBuilder;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;


public class GradleBuilder extends Builder {


    /**
     * The GradleBuilder build step description
     */
    private final String description;

    /**
     * The GradleBuilder command line switches
     */
    private final String switches;

    /**
     * The GradleBuilder tasks
     */
    private final String tasks;


    private final String rootBuildScriptDir;

    /**
     * The GradleBuilder build file path
     */
    private final String buildFile;

    /**
     * Identifies {@link GradleInstallation} to be used.
     */
    private final String gradleName;

    @DataBoundConstructor
    public GradleBuilder(String description, String switches, String tasks, String rootBuildScriptDir, String buildFile, String gradleName) {
        this.description = description;
        this.switches = switches;
        this.tasks = tasks;
        this.gradleName = gradleName;
        this.rootBuildScriptDir = rootBuildScriptDir;
        this.buildFile = buildFile;
    }


    public String getSwitches() {
        return switches;
    }

    public String getBuildFile() {
        return buildFile;
    }

    public String getGradleName() {
        return gradleName;
    }

    public String getTasks() {
        return tasks;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Gets the GradleBuilder to invoke,
     * or null to invoke the default one.
     */
    public GradleInstallation getGradle() {
        for (GradleInstallation i : getDescriptor().getInstallations()) {
            if (gradleName != null && i.getName().equals(gradleName))
                return i;
        }
        return null;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        EnvVars env = build.getEnvironment(listener);

        String normalizedSwitches = switches.replaceAll("[\t\r\n]+", " ");
        normalizedSwitches = Util.replaceMacro(normalizedSwitches, env);
        normalizedSwitches = Util.replaceMacro(normalizedSwitches, build.getBuildVariables());

        String normalizedTasks = tasks.replaceAll("[\t\r\n]+", " ");

        ArgumentListBuilder args = new ArgumentListBuilder();

        GradleInstallation ai = getGradle();
        if (ai == null) {
            args.add(launcher.isUnix() ? "gradle" : "gradle.bat");
        } else {
            ai = ai.forNode(Computer.currentComputer().getNode(), listener);
            ai = ai.forEnvironment(env);
            String exe = ai.getExecutable(launcher);
            if (exe == null) {
                listener.fatalError("ERROR");
                return false;
            }
            args.add(exe);
        }
        args.addKeyValuePairs("-D", build.getBuildVariables());
        args.addTokenized(normalizedSwitches);
        args.addTokenized(normalizedTasks);

        if (ai != null)
            env.put("GRADLE_HOME", ai.getHome());

        if (!launcher.isUnix()) {
            // on Windows, executing batch file can't return the correct error code,
            // so we need to wrap it into cmd.exe.
            // double %% is needed because we want ERRORLEVEL to be expanded after
            // batch file executed, not before. This alone shows how broken Windows is...
            args.prepend("cmd.exe", "/C");
            args.add("&&", "exit", "%%ERRORLEVEL%%");
        }

        FilePath rootLauncher = null;
        if (rootBuildScriptDir != null && rootBuildScriptDir.trim().length() != 0) {
            String rootBuildScriptNormalized = Util.replaceMacro(rootBuildScriptDir.trim(), env);
            rootBuildScriptNormalized = Util.replaceMacro(rootBuildScriptNormalized, build.getBuildVariableResolver());
            rootLauncher = new FilePath(build.getModuleRoot(), rootBuildScriptNormalized);
        } else {
            rootLauncher = build.getModuleRoot();
        }


        if (buildFile != null && buildFile.trim().length() != 0) {
            String buildFileNormalized = Util.replaceMacro(buildFile.trim(), env);
            args.add("-b");
            args.add(buildFileNormalized);
        }


        try {
            int r = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(rootLauncher).join();
            return r == 0;
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("command execution failed"));
            return false;
        }
    }



    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @CopyOnWrite
        private volatile GradleInstallation[] installations = new GradleInstallation[0];

        public DescriptorImpl() {
            load();
        }

        protected DescriptorImpl(Class<? extends GradleBuilder> clazz) {
            super(clazz);
        }

        /**
         * Obtains the {@link GradleInstallation.DescriptorImpl} instance.
         */
        public GradleInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(GradleInstallation.DescriptorImpl.class);
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        protected void convert(Map<String, Object> oldPropertyBag) {
            if (oldPropertyBag.containsKey("installations"))
                installations = (GradleInstallation[]) oldPropertyBag.get("installations");
        }

        public String getHelpFile() {
            return "/plugin/gradle/help.html";
        }

        public String getDisplayName() {
            return "Invoke GradleBuilder script";
        }

        public GradleInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(GradleInstallation... installations) {
            this.installations = installations;
            save();
        }

        @Override
        public GradleBuilder newInstance(StaplerRequest request, JSONObject formData) throws FormException {
            return (GradleBuilder) request.bindJSON(clazz, formData);
        }

    }


    public static final class GradleInstallation extends ToolInstallation
            implements EnvironmentSpecific<GradleInstallation>, NodeSpecific<GradleInstallation> {

        private final String gradleHome;

        @DataBoundConstructor
        public GradleInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
            super(name, launderHome(home), properties);
            this.gradleHome = super.getHome();
        }

        private static String launderHome(String home) {
            if (home.endsWith("/") || home.endsWith("\\")) {
                // see https://issues.apache.org/bugzilla/show_bug.cgi?id=26947
                // Ant doesn't like the trailing slash, especially on Windows
                return home.substring(0, home.length() - 1);
            } else {
                return home;
            }
        }

        /**
         * install directory.
         */
        public String getHome() {
            if (gradleHome != null) return gradleHome;
            return super.getHome();
        }


        public String getExecutable(Launcher launcher) throws IOException, InterruptedException {
            return launcher.getChannel().call(new Callable<String, IOException>() {
                public String call() throws IOException {
                    File exe = getExeFile();
                    if (exe.exists())
                        return exe.getPath();
                    return null;
                }
            });
        }

        private File getExeFile() {
            String execName;
            if (Functions.isWindows())
                execName = "gradle.bat";
            else
                execName = "gradle";

            String antHome = Util.replaceMacro(gradleHome, EnvVars.masterEnvVars);

            return new File(antHome, "bin/" + execName);
        }

        private static final long serialVersionUID = 1L;

        public GradleInstallation forEnvironment(EnvVars environment) {
            return new GradleInstallation(getName(), environment.expand(gradleHome), getProperties().toList());
        }

        public GradleInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
            return new GradleInstallation(getName(), translateFor(node, log), getProperties().toList());
        }

        @Extension
        public static class DescriptorImpl extends ToolDescriptor<GradleInstallation> {

            public DescriptorImpl() {
            }

            @Override
            public String getDisplayName() {
                return "GradleBuilder";
            }

            // for compatibility reasons, the persistence is done by GradleBuilder.DescriptorImpl

            @Override
            public GradleInstallation[] getInstallations() {
                return Hudson.getInstance().getDescriptorByType(GradleBuilder.DescriptorImpl.class).getInstallations();
            }

            @Override
            public void setInstallations(GradleInstallation... installations) {
                Hudson.getInstance().getDescriptorByType(GradleBuilder.DescriptorImpl.class).setInstallations(installations);
            }


        }
    }


}