/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.intellij.quarkus;

import com.intellij.facet.FacetManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.RootPolicy;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.intellij.lsp4mp4ij.psi.core.project.PsiMicroProfileProject;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.intellij.lsp4mp4ij.psi.internal.core.ls.PsiUtilsLSImpl;
import com.redhat.devtools.intellij.quarkus.facet.QuarkusFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuarkusModuleUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusModuleUtil.class);

    private static final Pattern QUARKUS_CORE_PATTERN = Pattern.compile("quarkus-core-(\\d[a-zA-Z\\d-.]+?).jar");

    public static final Pattern QUARKUS_STANDARD_VERSIONING = Pattern.compile("(\\d+).(\\d+).(\\d+)(.Final)?(-redhat-\\\\d+)?$");

    public static final Pattern APPLICATION_PROPERTIES = Pattern.compile("application(-.+)?\\.properties");

    public static final Pattern MICROPROFILE_CONFIG_PROPERTIES = Pattern.compile("microprofile-config(-.+)?\\.properties");

    public static final Pattern APPLICATION_YAML = Pattern.compile("application(-.+)?\\.ya?ml");

    private static final Comparator<VirtualFile> ROOT_COMPARATOR = Comparator.comparingInt(r -> r.getPath().length());

    /**
     * Check if the module is a Quarkus project. Should check if some class if present
     * but it seems PSI is not available when the module is added thus we rely on the
     * library names (io.quarkus:quarkus-core*).
     *
     * @param module the module to check
     * @return true if module is a Quarkus project and false otherwise.
     */
    public static boolean isQuarkusModule(@Nullable Module module) {
        return module != null && hasLibrary(module, QuarkusConstants.QUARKUS_CORE_PREFIX);
    }

    /**
     * Check if the module is a Quarkus Web Application project. Should check if some class if present
     * but it seems PSI is not available when the module is added thus we rely on the
     * library names (io.quarkus:quarkus-vertx-http:*).
     *
     * @param module the module to check
     * @return true if module is a Quarkus project and false otherwise.
     */
    public static boolean isQuarkusWebAppModule(Module module) {
        return hasLibrary(module, QuarkusConstants.QUARKUS_VERTX_HTTP_PREFIX);
    }

    private static boolean hasLibrary(Module module, String libraryNamePrefix) {
        OrderEnumerator libraries = ModuleRootManager.getInstance(module).orderEntries().librariesOnly();
        return libraries.process(new RootPolicy<Boolean>() {
            @Override
            public Boolean visitLibraryOrderEntry(@NotNull LibraryOrderEntry libraryOrderEntry, Boolean value) {
                return value || isLibrary(libraryOrderEntry, libraryNamePrefix);
            }
        }, false);
    }

    private static boolean isLibrary(@NotNull LibraryOrderEntry libraryOrderEntry, String libraryNamePrefix) {
        return libraryOrderEntry.getLibraryName() != null &&
                libraryOrderEntry.getLibraryName().contains(libraryNamePrefix);
    }

    /**
     * Checks whether the quarkus version used in this module matches the given predicate.
     * If we're unable to detect the Quarkus version, this method always returns false.
     * The predicate is based on a matcher that is based on the QUARKUS_STANDARD_VERSIONING regular expression,
     * that means that `matcher.group(1)` returns the major version, `matcher.group(2)` returns the minor version,
     * `matcher.group(3)` returns the patch version.
     * If the detected Quarkus version does not follow the standard versioning, the matcher does not match at all.
     * If we can't detect the Quarkus version, the returned value will be the value of the `returnIfNoQuarkusDetected` parameter.
     */
    public static boolean checkQuarkusVersion(Module module, Predicate<Matcher> predicate, boolean returnIfNoQuarkusDetected) {
        Optional<VirtualFile> quarkusCoreJar = Arrays.stream(ModuleRootManager.getInstance(module).orderEntries()
                        .runtimeOnly()
                        .classes()
                        .getRoots())
                .filter(f -> Pattern.matches(QUARKUS_CORE_PATTERN.pattern(), f.getName()))
                .findFirst();
        if (quarkusCoreJar.isPresent()) {
            Matcher quarkusCoreArtifactMatcher = QUARKUS_CORE_PATTERN.matcher(quarkusCoreJar.get().getName());
            if (quarkusCoreArtifactMatcher.matches()) {
                String quarkusVersion = quarkusCoreArtifactMatcher.group(1);
                LOGGER.debug("Detected Quarkus version = {}", quarkusVersion);
                Matcher quarkusVersionMatcher = QUARKUS_STANDARD_VERSIONING.matcher(quarkusVersion);
                return predicate.test(quarkusVersionMatcher);
            } else {
                return false;
            }
        } else {
            return returnIfNoQuarkusDetected;
        }
    }

    public static Set<String> getModulesURIs(Project project) {
        Set<String> uris = new HashSet<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            uris.add(PsiUtilsLSImpl.getProjectURI(module));
        }
        return uris;
    }

    public static boolean isQuarkusPropertiesFile(VirtualFile file, Project project) {
        if (APPLICATION_PROPERTIES.matcher(file.getName()).matches() ||
                MICROPROFILE_CONFIG_PROPERTIES.matcher(file.getName()).matches()) {
            return isQuarkusModule(file, project);
        }
        return false;
    }

    public static boolean isQuarkusYamlFile(@NotNull VirtualFile file) {
        return APPLICATION_YAML.matcher(file.getName()).matches();
    }

    public static boolean isQuarkusYamlFile(VirtualFile file, Project project) {
        if (isQuarkusYamlFile(file)) {
            return isQuarkusModule(file, project);
        }
        return false;
    }

    private static boolean isQuarkusModule(VirtualFile file, Project project) {
        Module module = LSPIJUtils.getModule(file, project);
        return module != null && (FacetManager.getInstance(module).getFacetByType(QuarkusFacet.FACET_TYPE_ID) != null || QuarkusModuleUtil.isQuarkusModule(module));
    }

    public static @Nullable VirtualFile getModuleDirPath(@NotNull Module module) {
        VirtualFile[] roots = getContentRoots(module);
        if (roots.length > 0) {
            return roots[0];
        }
        return VfsUtil.findFileByIoFile(new File(ModuleUtilCore.getModuleDirPath(module)), true);
    }

    /**
     * Returns an array of content roots of the given module sorted with smallest path first (to eliminate generated sources roots) from all content entries.
     *
     * @param module the module
     * @return the array of content roots.
     */
    public static VirtualFile[] getContentRoots(Module module) {
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        if (roots.length <= 1) {
            return roots;
        }
        // put root with smallest path first (eliminates generated sources roots)
        sortRoot(roots);
        return roots;
    }

    public static void sortRoot(List<VirtualFile> roots) {
        Collections.sort(roots, ROOT_COMPARATOR); // put root with smallest path first (eliminates generated sources roots)
    }

    public static void sortRoot(VirtualFile[] roots) {
        Arrays.sort(roots, ROOT_COMPARATOR); // put root with smallest path first (eliminates generated sources roots)
    }

    public static String getApplicationUrl(@NotNull PsiMicroProfileProject mpProject) {
        int port = getPort(mpProject);
        String path = mpProject.getProperty("quarkus.http.root-path", "/");
        return "http://localhost:" + port + normalize(path);
    }

    public static String getDevUIUrl(@NotNull PsiMicroProfileProject mpProject) {
        int port = getPort(mpProject);
        String path = mpProject.getProperty("quarkus.http.non-application-root-path", "q");
        if (!path.startsWith("/")) {
            String rootPath = mpProject.getProperty("quarkus.http.root-path", "/");
            path = normalize(rootPath) + path;
        }
        return "http://localhost:" + port + normalize(path) + "dev";
    }

    private static String normalize(String path) {
        StringBuilder builder = new StringBuilder(path);
        if (builder.isEmpty() || builder.charAt(0) != '/') {
            builder.insert(0, '/');
        }
        if (builder.charAt(builder.length() - 1) != '/') {
            builder.append('/');
        }
        return builder.toString();
    }

    private static int getPort(@NotNull PsiMicroProfileProject mpProject) {
        int port = mpProject.getPropertyAsInteger("quarkus.http.port", 8080);
        return mpProject.getPropertyAsInteger("%dev.quarkus.http.port", port);
    }

}
