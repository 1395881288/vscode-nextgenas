/*
Copyright 2016-2018 Bowler Hat LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.as3mxml.vscode.utils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.as3mxml.asconfigc.compiler.ProjectType;
import com.as3mxml.vscode.project.ProjectOptions;
import com.as3mxml.vscode.project.VSCodeConfiguration;

import org.apache.royale.compiler.clients.MXMLJSC;
import org.apache.royale.compiler.config.ICompilerSettingsConstants;
import org.apache.royale.compiler.driver.IBackend;
import org.apache.royale.compiler.internal.driver.js.goog.JSGoogConfiguration;
import org.apache.royale.compiler.internal.driver.js.jsc.JSCBackend;
import org.apache.royale.compiler.internal.driver.js.node.NodeBackend;
import org.apache.royale.compiler.internal.driver.js.node.NodeModuleBackend;
import org.apache.royale.compiler.internal.driver.js.royale.RoyaleBackend;
import org.apache.royale.compiler.internal.projects.RoyaleJSProject;
import org.apache.royale.compiler.internal.projects.RoyaleProject;
import org.apache.royale.compiler.internal.projects.RoyaleProjectConfigurator;
import org.apache.royale.compiler.internal.workspaces.Workspace;
import org.apache.royale.compiler.problems.ICompilerProblem;
import org.apache.royale.compiler.targets.ITarget;
import org.apache.royale.compiler.targets.ITargetSettings;

public class CompilerProjectUtils
{
    private static final String CONFIG_ROYALE = "royale";
    private static final String CONFIG_JS = "js";
	private static final String CONFIG_NODE = "node";
	
    private static final String TOKEN_CONFIGNAME = "configname";
    private static final String TOKEN_ROYALELIB = "royalelib";
	private static final String TOKEN_FLEXLIB = "flexlib";
	
    private static final String PROPERTY_FRAMEWORK_LIB = "royalelib";

	private static final Pattern ADDITIONAL_OPTIONS_PATTERN = Pattern.compile("[^\\s]*'([^'])*?'|[^\\s]*\"([^\"])*?\"|[^\\s]+");

	public static RoyaleProject createProject(ProjectOptions currentProjectOptions, Workspace compilerWorkspace)
	{
        Path frameworkLibPath = Paths.get(System.getProperty(PROPERTY_FRAMEWORK_LIB));
        boolean frameworkSDKIsRoyale = ActionScriptSDKUtils.isRoyaleFramework(frameworkLibPath);

        Path asjscPath = frameworkLibPath.resolve("../js/bin/asjsc");
        boolean frameworkSDKIsFlexJS = !frameworkSDKIsRoyale && asjscPath.toFile().exists();

		RoyaleProject project = null;

        //we're going to try to determine what kind of project we need
        //(either Royale or everything else). if it's a Royale project, we
        //should choose an appropriate backend.
        IBackend backend = null;

        //first, start by looking if the targets compiler option is
        //specified. if it is, we definitely have a Royale project. we'll
        //use the first target value as the indicator of what the user
        //thinks is most important for code intelligence (native JS classes
        //or native SWF classes?)
        //this isn't ideal because it would be better if we could provide
        //code intelligence for all targets simultaneously, but this is a
        //limitation that we need to live with, for now.
        List<String> targets = currentProjectOptions.targets;
        if (targets != null && targets.size() > 0)
        {
            //first, check if any targets are specified
            String firstTarget = targets.get(0);
            switch (MXMLJSC.JSTargetType.fromString(firstTarget))
            {
                case JS_NATIVE:
                {
                    backend = new JSCBackend();
                    break;
                }
                case JS_NODE:
                {
                    backend = new NodeBackend();
                    break;
                }
                case JS_NODE_MODULE:
                {
                    backend = new NodeModuleBackend();
                    break;
                }
                default:
                {
                    //SWF and JSRoyale should both use this backend.

                    //previously, we didn't use a backend for SWF, but after
                    //FlexJS became Royale, something changed in the
                    //compiler to make it more strict.

                    //it actually shouldn't matter too much which JS
                    //backend is used when we're only using the project for
                    //code intelligence, so this is probably an acceptable
                    //fallback for just about everything.
                    backend = new RoyaleBackend();
                    break;
                }
            }
        }
        //if no targets are specified, we can guess whether it's a Royale
        //project based on the config value.
        else if (currentProjectOptions.config.equals(CONFIG_ROYALE))
        {
            backend = new RoyaleBackend();
        }
        else if (currentProjectOptions.config.equals(CONFIG_JS))
        {
            backend = new JSCBackend();
        }
        else if (currentProjectOptions.config.equals(CONFIG_NODE))
        {
            backend = new NodeBackend();
        }
        //finally, if the config value is missing, then choose a decent
        //default backend when the SDK is Royale
        else if (frameworkSDKIsRoyale || frameworkSDKIsFlexJS)
        {
            backend = new RoyaleBackend();
        }

        //if we created a backend, it's a Royale project (RoyaleJSProject)
        if (backend != null)
        {
            project = new RoyaleJSProject(compilerWorkspace, backend);
        }
        //if we haven't created the project yet, then it's not Royale and
        //the project should be one that doesn't require a backend.
        if (project == null)
        {
            //yes, this is called RoyaleProject, but a *real* Royale project
            //is RoyaleJSProject... even if it's SWF only! confusing, right?
            project = new RoyaleProject(compilerWorkspace);
        }
        project.setProblems(new ArrayList<>());
        return project;
    }

    public static RoyaleProjectConfigurator configureProject(RoyaleProject project, ProjectOptions currentProjectOptions, Collection<ICompilerProblem> problems)
    {
        Path frameworkLibPath = Paths.get(System.getProperty(PROPERTY_FRAMEWORK_LIB));
        boolean frameworkSDKIsRoyale = ActionScriptSDKUtils.isRoyaleFramework(frameworkLibPath);

        //check if the framework SDK doesn't include the Spark theme
        Path sparkPath = frameworkLibPath.resolve("./themes/Spark/spark.css");
        boolean frameworkSDKContainsSparkTheme = sparkPath.toFile().exists();

		List<String> compilerOptions = currentProjectOptions.compilerOptions;
        RoyaleProjectConfigurator configurator = null;
        if (project instanceof RoyaleJSProject)
        {
            configurator = new RoyaleProjectConfigurator(JSGoogConfiguration.class);
        }
        else //swf only
        {
            configurator = new RoyaleProjectConfigurator(VSCodeConfiguration.class);
        }
        if(frameworkSDKIsRoyale)
        {
            configurator.setToken(TOKEN_ROYALELIB, System.getProperty(PROPERTY_FRAMEWORK_LIB));
        }
        else //not royale
        {
            configurator.setToken(TOKEN_FLEXLIB, System.getProperty(PROPERTY_FRAMEWORK_LIB));
        }
        configurator.setToken(TOKEN_CONFIGNAME, currentProjectOptions.config);
        String projectType = currentProjectOptions.type;
        String[] files = currentProjectOptions.files;
        String additionalOptions = currentProjectOptions.additionalOptions;
        ArrayList<String> combinedOptions = new ArrayList<>();
        if (compilerOptions != null)
        {
            combinedOptions.addAll(compilerOptions);
        }
        if (additionalOptions != null)
        {
            //split the additionalOptions into separate values so that we can
            //pass them in as String[], as the compiler expects.
            Matcher matcher = ADDITIONAL_OPTIONS_PATTERN.matcher(additionalOptions);
            while (matcher.find())
            {
                String option = matcher.group();
                combinedOptions.add(option);
            }
        }

        //Github #245: avoid errors from -inline
        combinedOptions.removeIf((option) ->
        {
            return option.equals("-inline")
                    || option.equals("--inline")
                    || option.equals("-inline=true")
                    || option.equals("--inline=true");
        });
        
        //not all framework SDKs support a theme (such as Adobe's AIR SDK), so
        //we clear it for the editor to avoid a missing spark.css file.
        if (!frameworkSDKContainsSparkTheme)
        {
            combinedOptions.add("-theme=");
        }
        if (projectType.equals(ProjectType.LIB))
        {
            configurator.setConfiguration(combinedOptions.toArray(new String[combinedOptions.size()]),
                    ICompilerSettingsConstants.INCLUDE_CLASSES_VAR, false);
        }
        else // app
        {
            combinedOptions.addAll(Arrays.asList(files));
            configurator.setConfiguration(combinedOptions.toArray(new String[combinedOptions.size()]),
                    ICompilerSettingsConstants.FILE_SPECS_VAR);
        }
        //this needs to be set before applyToProject() so that it's in the
        //configuration buffer before addExternalLibraryPath() is called
        configurator.setExcludeNativeJSLibraries(false);
        Path appendConfigPath = Paths.get(System.getProperty(PROPERTY_FRAMEWORK_LIB));
        appendConfigPath = appendConfigPath.resolve("../ide/vscode-nextgenas/vscode-nextgenas-config.xml");
        File appendConfigFile = appendConfigPath.toFile();
        if (appendConfigFile.exists())
        {
            configurator.addConfiguration(appendConfigFile);
        }
		boolean result = configurator.applyToProject(project);
		problems.addAll(configurator.getConfigurationProblems());
        if (!result)
        {
            return null;
        }
        ITarget.TargetType targetType = ITarget.TargetType.SWF;
        if (currentProjectOptions.type.equals(ProjectType.LIB))
        {
            targetType = ITarget.TargetType.SWC;
        }
        ITargetSettings targetSettings = configurator.getTargetSettings(targetType);
        if (targetSettings == null)
        {
            System.err.println("Failed to get compile settings for +configname=" + currentProjectOptions.config + ".");
            return null;
        }
        project.setTargetSettings(targetSettings);
        return configurator;
	}
}