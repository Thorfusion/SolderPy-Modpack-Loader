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
        selected.addAll(manifest.selectionPolicy.requiredMemberships);
        selected.addAll(requestedMemberships);

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

    private static void closeDependencies(
        Set<Long> selected,
        Map<Long, BootstrapManifest.Package> byMembership) throws LoaderException {

        Queue<Long> pending = new ArrayDeque<Long>(selected);
        while (!pending.isEmpty()) {
            BootstrapManifest.Package item = byMembership.get(pending.remove());
            if (item == null) {
                throw new LoaderException("The manifest selected an unknown package membership");
            }
            for (BootstrapManifest.Dependency dependency : item.dependencies) {
                if (!dependency.required) {
                    continue;
                }
                if (!dependency.present || dependency.membershipId == null ||
                    !byMembership.containsKey(dependency.membershipId)) {
                    throw new LoaderException("Package " + item.name +
                        " requires unavailable dependency " + dependency.name);
                }
                if (selected.add(dependency.membershipId)) {
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
