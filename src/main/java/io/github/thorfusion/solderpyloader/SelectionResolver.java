package io.github.thorfusion.solderpyloader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class SelectionResolver {
    private SelectionResolver() {
    }

    static List<BootstrapManifest.Package> resolve(BootstrapManifest manifest)
        throws LoaderException {

        return resolve(manifest, manifest.selectionPolicy.defaultMemberships);
    }

    static List<BootstrapManifest.Package> resolve(
        BootstrapManifest manifest, Collection<Long> requestedMemberships)
        throws LoaderException {

        Map<Long, BootstrapManifest.Package> byMembership =
            new LinkedHashMap<Long, BootstrapManifest.Package>();
        for (BootstrapManifest.Package item : manifest.packages) {
            byMembership.put(item.membershipId, item);
        }

        Set<Long> selected = new LinkedHashSet<Long>();
        addLoaderOwned(selected, manifest.selectionPolicy.requiredMemberships, byMembership);
        addLoaderOwned(selected, requestedMemberships, byMembership);

        closeDependencies(selected, byMembership);
        enforceGroups(manifest.groups, selected);

        List<BootstrapManifest.Package> result = new ArrayList<BootstrapManifest.Package>();
        for (BootstrapManifest.Package item : manifest.packages) {
            if (item.bootstrapManaged && selected.contains(item.membershipId)) {
                result.add(item);
            }
        }
        return result;
    }

    private static void addLoaderOwned(
        Set<Long> selected,
        Collection<Long> memberships,
        Map<Long, BootstrapManifest.Package> byMembership) throws LoaderException {

        for (Long membership : memberships) {
            BootstrapManifest.Package item = byMembership.get(membership);
            if (item == null) {
                throw new LoaderException("The manifest selected an unknown package membership");
            }
            if (item.bootstrapManaged) {
                selected.add(membership);
            }
        }
    }

    private static void closeDependencies(
        Set<Long> selected,
        Map<Long, BootstrapManifest.Package> byMembership) throws LoaderException {

        // Native packages are installed by the launcher, but their required
        // Loader-owned dependencies must still be included in our plan.
        // Start from both ownership sets and traverse launcher-owned nodes as
        // part of the dependency graph without adding them to the install set.
        Queue<Long> pending = new ArrayDeque<Long>(selected);
        for (BootstrapManifest.Package item : byMembership.values()) {
            if ("launcher".equals(item.installOwner)) {
                pending.add(item.membershipId);
            }
        }
        Set<Long> visited = new LinkedHashSet<Long>();
        while (!pending.isEmpty()) {
            Long membership = pending.remove();
            if (!visited.add(membership)) {
                continue;
            }
            BootstrapManifest.Package item = byMembership.get(membership);
            if (item == null) {
                throw new LoaderException("The manifest selected an unknown package membership");
            }
            for (BootstrapManifest.Dependency dependency : item.dependencies) {
                if (!dependency.required) {
                    continue;
                }
                BootstrapManifest.Package dependencyItem = dependency.membershipId == null
                    ? null : byMembership.get(dependency.membershipId);
                if (!dependency.present || dependencyItem == null) {
                    throw new LoaderException("Package " + item.name +
                        " requires unavailable dependency " + dependency.name);
                }
                if (dependencyItem.bootstrapManaged) {
                    selected.add(dependency.membershipId);
                    pending.add(dependency.membershipId);
                } else if ("launcher".equals(dependencyItem.installOwner)) {
                    pending.add(dependency.membershipId);
                }
            }
        }
    }

    private static void enforceGroups(
        List<BootstrapManifest.Group> groups, Set<Long> selected) throws LoaderException {
        for (BootstrapManifest.Group group : groups) {
            int count = 0;
            for (BootstrapManifest.Choice choice : group.choices) {
                if (selected.contains(choice.membershipId)) {
                    count++;
                }
            }
            if (count < group.minimum || (group.maximum != null && count > group.maximum)) {
                String maximum = group.maximum == null ? "unbounded" : group.maximum.toString();
                throw new LoaderException("Optional group '" + group.key + "' selected " + count +
                    " package(s), but requires " + group.minimum + ".." + maximum);
            }
        }
    }
}
