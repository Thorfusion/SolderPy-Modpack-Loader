package io.github.thorfusion.solderpyloader;

interface ManifestSource {
    BootstrapManifest load(InstalledState previous, InstalledState next, boolean samePack)
        throws LoaderException;

    BootstrapManifest previousManifest(InstalledState previous) throws LoaderException;
}
