package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionResolverTest {
    @Test
    void advancedDefaultsAndDependenciesAreClosed() throws Exception {
        BootstrapManifest manifest = manifest();

        List<BootstrapManifest.Package> selected = SelectionResolver.resolve(manifest);

        assertEquals(Arrays.asList("core", "pretty-world"), selected.stream()
            .map(item -> item.name).collect(Collectors.toList()));
    }

    @Test
    void advancedSelectionComesFromApiMemberships() throws Exception {
        BootstrapManifest manifest = manifest();
        manifest.selectionPolicy.defaultMemberships = Arrays.asList(11L, 13L);

        List<BootstrapManifest.Package> selected = SelectionResolver.resolve(manifest);

        assertEquals(Arrays.asList("core", "fast-world"), selected.stream()
            .map(item -> item.name).collect(Collectors.toList()));
    }

    @Test
    void interactiveAdvancedChoiceOverridesApiDefault() throws Exception {
        BootstrapManifest manifest = manifest();

        List<BootstrapManifest.Package> selected =
            SelectionResolver.resolve(manifest, Arrays.asList(11L, 13L));

        assertEquals(Arrays.asList("core", "fast-world"), selected.stream()
            .map(item -> item.name).collect(Collectors.toList()));
    }

    @Test
    void dependencyConflictWithSingleGroupFails() {
        BootstrapManifest manifest = manifest();
        BootstrapManifest.Dependency dependency = new BootstrapManifest.Dependency();
        dependency.name = "fast-world";
        dependency.required = true;
        dependency.present = true;
        dependency.membershipId = 13L;
        manifest.packages.get(1).dependencies.add(dependency);

        assertThrows(LoaderException.class,
            () -> SelectionResolver.resolve(manifest));
    }

    @Test
    void apiCanSelectNoChoicesForOptionalMultipleGroup() throws Exception {
        BootstrapManifest manifest = manifest();
        BootstrapManifest.Group group = manifest.groups.get(0);
        group.selectionType = "multiple";
        group.minimum = 0;
        group.maximum = null;
        manifest.selectionPolicy.defaultMemberships = Collections.singletonList(11L);

        List<BootstrapManifest.Package> selected = SelectionResolver.resolve(manifest);

        assertEquals(Collections.singletonList("core"), selected.stream()
            .map(item -> item.name).collect(Collectors.toList()));
    }

    @Test
    void basicOptionalSelectionComesFromApiMemberships() throws Exception {
        BootstrapManifest manifest = manifest();
        manifest.optionalMode.name = "basic";
        manifest.groups = Collections.emptyList();
        manifest.packages.get(1).selection.state = 1;
        manifest.packages.get(1).selection.groupKey = null;
        manifest.selectionPolicy.defaultMemberships = Arrays.asList(11L, 12L);

        List<BootstrapManifest.Package> selected = SelectionResolver.resolve(manifest);

        assertEquals(Arrays.asList("core", "pretty-world"), selected.stream()
            .map(item -> item.name).collect(Collectors.toList()));
    }

    @Test
    void screenFindsBasicOptionalPackages() {
        BootstrapManifest manifest = manifest();
        manifest.optionalMode.name = "basic";
        manifest.groups = Collections.emptyList();
        manifest.packages.get(1).selection.state = 1;
        manifest.packages.get(1).selection.groupKey = null;
        manifest.packages.get(2).selection.groupKey = null;

        assertEquals(new LinkedHashSet<Long>(Collections.singletonList(12L)),
            OptionalSelectionScreen.selectableMemberships(manifest));
    }

    @Test
    void screenFindsAdvancedGroupsAndUngroupedOptionals() {
        BootstrapManifest manifest = manifest();
        BootstrapManifest.Package optional = item(14, "map", 1, null);
        manifest.packages = Arrays.asList(
            manifest.packages.get(0), manifest.packages.get(1), manifest.packages.get(2), optional);

        assertEquals(new LinkedHashSet<Long>(Arrays.asList(12L, 13L, 14L)),
            OptionalSelectionScreen.selectableMemberships(manifest));
    }

    @Test
    void existingOptionalChoicesPersistWhileNewOptionsUseApiDefaults() {
        BootstrapManifest previousManifest = manifest();
        InstalledState previous = new InstalledState();
        previous.manifest = previousManifest;
        previous.selectedMemberships = Arrays.asList(11L, 13L);

        BootstrapManifest current = manifest();
        BootstrapManifest.Package newOption = item(14, "map", 1, null);
        current.packages = Arrays.asList(
            current.packages.get(0), current.packages.get(1),
            current.packages.get(2), newOption);
        current.selectionPolicy.defaultMemberships = Arrays.asList(11L, 12L, 14L);

        assertEquals(new LinkedHashSet<Long>(Arrays.asList(11L, 14L, 13L)),
            new LinkedHashSet<Long>(
                BootstrapEngine.initialMemberships(current, previous, true)));
    }

    @Test
    void serverSelectionAlwaysStartsFromApiDefaults() {
        BootstrapManifest current = manifest();
        InstalledState previous = new InstalledState();
        previous.manifest = current;
        previous.selectedMemberships = Arrays.asList(11L, 13L);

        assertEquals(new LinkedHashSet<Long>(Arrays.asList(11L, 12L)),
            new LinkedHashSet<Long>(
                BootstrapEngine.initialMemberships(current, previous, false)));
    }

    @Test
    void identicalStoredManifestReusesSavedSelectionsWithoutPrompting() {
        BootstrapManifest current = versionedManifest('a');
        InstalledState previous = new InstalledState();
        previous.build = "1.0";
        previous.manifestHash = repeat('a', 64);
        previous.manifest = current;
        previous.selectedMemberships = Arrays.asList(11L, 13L);

        assertTrue(BootstrapEngine.sameManifest(previous, current));
        assertTrue(BootstrapEngine.canReuseSavedSelections(current, previous, true));
        assertFalse(BootstrapEngine.canReuseSavedSelections(current, previous, false));
    }

    @Test
    void changedManifestOrInvalidSavedSelectionRequiresPrompting() {
        BootstrapManifest current = versionedManifest('b');
        InstalledState previous = new InstalledState();
        previous.build = "1.0";
        previous.manifestHash = repeat('a', 64);
        previous.manifest = current;
        previous.selectedMemberships = Collections.singletonList(999L);

        assertFalse(BootstrapEngine.sameManifest(previous, current));
        assertFalse(BootstrapEngine.canReuseSavedSelections(current, previous, true));
    }

    private static BootstrapManifest manifest() {
        BootstrapManifest manifest = new BootstrapManifest();
        manifest.optionalMode = new BootstrapManifest.OptionalMode();
        manifest.optionalMode.name = "advanced";
        manifest.selectionPolicy = new BootstrapManifest.SelectionPolicy();
        manifest.selectionPolicy.requiredMemberships.add(11L);
        manifest.selectionPolicy.defaultMemberships.add(11L);
        manifest.selectionPolicy.defaultMemberships.add(12L);

        BootstrapManifest.Package core = item(11, "core", 0, null);
        BootstrapManifest.Package pretty = item(12, "pretty-world", 2, "World style");
        BootstrapManifest.Package fast = item(13, "fast-world", 2, "World style");

        BootstrapManifest.Dependency coreDependency = new BootstrapManifest.Dependency();
        coreDependency.name = "core";
        coreDependency.required = true;
        coreDependency.present = true;
        coreDependency.membershipId = 11L;
        pretty.dependencies.add(coreDependency);
        fast.dependencies.add(coreDependency);
        manifest.packages = Arrays.asList(core, pretty, fast);

        BootstrapManifest.Group group = new BootstrapManifest.Group();
        group.key = "World style";
        group.minimum = 1;
        group.maximum = 1;
        group.choices = Arrays.asList(choice(12, "pretty-world", true),
            choice(13, "fast-world", false));
        manifest.groups = Collections.singletonList(group);
        return manifest;
    }

    private static BootstrapManifest versionedManifest(char hashCharacter) {
        BootstrapManifest manifest = manifest();
        manifest.build = new BootstrapManifest.Build();
        manifest.build.version = "1.0";
        manifest.manifestHash = repeat(hashCharacter, 64);
        return manifest;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static BootstrapManifest.Package item(long membership, String slug, int state, String group) {
        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.membershipId = membership;
        item.name = slug;
        item.version = "1.0";
        item.bootstrapManaged = true;
        item.download = new BootstrapManifest.Download();
        item.download.md5 = "a" + String.format("%031d", membership);
        item.selection = new BootstrapManifest.Selection();
        item.selection.state = state;
        item.selection.groupKey = group;
        return item;
    }

    private static BootstrapManifest.Choice choice(long membership, String slug, boolean selected) {
        BootstrapManifest.Choice choice = new BootstrapManifest.Choice();
        choice.membershipId = membership;
        choice.slug = slug;
        choice.selectedByDefault = selected;
        return choice;
    }
}
