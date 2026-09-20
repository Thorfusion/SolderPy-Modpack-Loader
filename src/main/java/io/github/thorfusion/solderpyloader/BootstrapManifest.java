package io.github.thorfusion.solderpyloader;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapManifest {
    String schema;
    @SerializedName("schema_version") int schemaVersion;
    Modpack modpack;
    Build build;
    String target;
    String source;
    @SerializedName("optional_mode") OptionalMode optionalMode;
    @SerializedName("selection_policy") SelectionPolicy selectionPolicy;
    List<Group> groups = new ArrayList<Group>();
    List<Package> packages = new ArrayList<Package>();
    @SerializedName("manifest_hash") String manifestHash;

    static final class Modpack {
        long id;
        String slug;
        String name;
    }

    static final class Build {
        long id;
        String version;
        String minecraft;
        String modloader;
        @SerializedName("modloader_version") String modloaderVersion;
        String java;
        @SerializedName("java_runtime") String javaRuntime;
        long memory;
    }

    static final class OptionalMode {
        int id;
        String name;
    }

    static final class SelectionPolicy {
        Map<String, String> states = new LinkedHashMap<String, String>();
        @SerializedName("ignored_modtypes") List<String> ignoredModtypes = new ArrayList<String>();
        @SerializedName("required_memberships") List<Long> requiredMemberships = new ArrayList<Long>();
        @SerializedName("default_memberships") List<Long> defaultMemberships = new ArrayList<Long>();
    }

    static final class Group {
        long id;
        String key;
        String name;
        String description;
        @SerializedName("selection_type") String selectionType;
        int minimum;
        Integer maximum;
        @SerializedName("sort_order") int sortOrder;
        List<Choice> choices = new ArrayList<Choice>();
    }

    static final class Choice {
        @SerializedName("membership_id") long membershipId;
        String slug;
        @SerializedName("selected_by_default") boolean selectedByDefault;
    }

    static final class Package {
        long id;
        @SerializedName("membership_id") long membershipId;
        String name;
        @SerializedName("pretty_name") String prettyName;
        String description;
        String version;
        String type;
        String modtype;
        @SerializedName("bootstrap_managed") boolean bootstrapManaged;
        Download download;
        Selection selection;
        List<Dependency> dependencies = new ArrayList<Dependency>();
    }

    static final class Download {
        String url;
        String md5;
        Long filesize;
        String format;
        String path;
        @SerializedName("extract_to") String extractTo;
        List<DownloadSource> sources = new ArrayList<DownloadSource>();
    }

    static final class DownloadSource {
        String provider;
        String url;
    }

    static final class Selection {
        int state;
        @SerializedName("state_name") String stateName;
        @SerializedName("group_id") Long groupId;
        @SerializedName("group_key") String groupKey;
        @SerializedName("selected_by_default") boolean selectedByDefault;
    }

    static final class Dependency {
        long id;
        String name;
        boolean required;
        boolean present;
        @SerializedName("membership_id") Long membershipId;
        String version;
    }

    void validate(String expectedModpack, String expectedTarget, String expectedSource)
        throws LoaderException {
        if (!"solder.py/bootstrap".equals(schema)) {
            throw new LoaderException("Server returned an unsupported bootstrap schema: " + schema);
        }
        if (schemaVersion != 1) {
            throw new LoaderException("Unsupported bootstrap schema version: " + schemaVersion);
        }
        if (modpack == null || !expectedModpack.equals(modpack.slug)) {
            throw new LoaderException("Bootstrap manifest is for the wrong modpack");
        }
        if (build == null || isBlank(build.version)) {
            throw new LoaderException("Bootstrap manifest is missing its resolved build version");
        }
        if (!expectedTarget.equals(target)) {
            throw new LoaderException("Bootstrap manifest target does not match the configuration");
        }
        // Early schema-1 servers predate the explicit source response field and
        // behaved like today's hybrid mode. Keep that default compatible while
        // still refusing a server that cannot confirm an explicit solder mode.
        if (isBlank(source)) {
            source = "hybrid";
        }
        if (!expectedSource.equals(source)) {
            throw new LoaderException("Bootstrap manifest source does not match the configuration");
        }
        if (optionalMode == null ||
            !("basic".equals(optionalMode.name) || "advanced".equals(optionalMode.name))) {
            throw new LoaderException("Bootstrap manifest has an invalid optional mode");
        }
        if (selectionPolicy == null || selectionPolicy.requiredMemberships == null ||
            selectionPolicy.defaultMemberships == null || packages == null || groups == null) {
            throw new LoaderException("Bootstrap manifest is missing selection data");
        }
        if (isBlank(manifestHash) || !manifestHash.matches("[0-9a-fA-F]{64}")) {
            throw new LoaderException("Bootstrap manifest has an invalid manifest_hash");
        }

        Map<Long, Package> memberships = new LinkedHashMap<Long, Package>();
        Map<String, Package> names = new LinkedHashMap<String, Package>();
        for (Package item : packages) {
            if (item == null || item.membershipId <= 0 || isBlank(item.name) || isBlank(item.version)) {
                throw new LoaderException("Bootstrap manifest contains an incomplete package");
            }
            if (memberships.put(item.membershipId, item) != null) {
                throw new LoaderException("Duplicate package membership ID: " + item.membershipId);
            }
            if (names.put(item.name, item) != null) {
                throw new LoaderException("Duplicate package slug: " + item.name);
            }
            if (item.selection == null || item.selection.state < 0 || item.selection.state > 2) {
                throw new LoaderException("Package " + item.name + " has an invalid selection state");
            }
            if (item.dependencies == null) {
                item.dependencies = new ArrayList<Dependency>();
            }
            if (item.bootstrapManaged) {
                validateDownload(item);
            }
        }
        for (Long membership : selectionPolicy.requiredMemberships) {
            if (!memberships.containsKey(membership)) {
                throw new LoaderException("Selection policy references an unknown required membership");
            }
        }
        for (Long membership : selectionPolicy.defaultMemberships) {
            if (!memberships.containsKey(membership)) {
                throw new LoaderException("Selection policy references an unknown default membership");
            }
        }

        Set<String> groupKeys = new HashSet<String>();
        Set<Long> groupedMemberships = new HashSet<Long>();
        for (Group group : groups) {
            if (group == null || isBlank(group.key) || !groupKeys.add(group.key) ||
                !("single".equals(group.selectionType) || "multiple".equals(group.selectionType)) ||
                group.minimum < 0 || (group.maximum != null && group.maximum < group.minimum) ||
                group.choices == null || group.choices.isEmpty()) {
                throw new LoaderException("Bootstrap manifest contains an invalid optional group");
            }
            if ("single".equals(group.selectionType) &&
                (group.minimum != 1 || group.maximum == null || group.maximum.intValue() != 1)) {
                throw new LoaderException("Single-choice group '" + group.key + "' must require exactly one choice");
            }
            Set<String> choiceSlugs = new HashSet<String>();
            for (Choice choice : group.choices) {
                Package item = choice == null ? null : memberships.get(choice.membershipId);
                if (item == null || !item.name.equals(choice.slug) || !choiceSlugs.add(choice.slug) ||
                    !groupedMemberships.add(choice.membershipId) || item.selection == null ||
                    !group.key.equals(item.selection.groupKey)) {
                    throw new LoaderException("Optional group '" + group.key + "' has an invalid choice");
                }
            }
        }
    }

    private static void validateDownload(Package item) throws LoaderException {
        Download download = item.download;
        if (download == null || isBlank(download.url) ||
            isBlank(download.md5) || !download.md5.matches("[0-9a-fA-F]{32}")) {
            throw new LoaderException("Package " + item.name + " has invalid download metadata");
        }
        if (download.filesize != null && download.filesize.longValue() < 0) {
            throw new LoaderException("Package " + item.name + " has an invalid download size");
        }
        if (download.sources == null) {
            download.sources = new ArrayList<DownloadSource>();
        }
        Set<String> sourceUrls = new HashSet<String>();
        for (DownloadSource source : download.sources) {
            if (source == null || isBlank(source.provider) || isBlank(source.url) ||
                !sourceUrls.add(source.url)) {
                throw new LoaderException("Package " + item.name + " has invalid download sources");
            }
        }
        if ("jar".equals(download.format)) {
            if (isBlank(download.path)) {
                throw new LoaderException("JAR package " + item.name + " has no output path");
            }
        } else if ("solder_zip".equals(download.format)) {
            if (isBlank(download.extractTo)) {
                throw new LoaderException("ZIP package " + item.name + " has no extraction path");
            }
        } else {
            throw new LoaderException("Package " + item.name +
                " has unsupported download format: " + download.format);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
