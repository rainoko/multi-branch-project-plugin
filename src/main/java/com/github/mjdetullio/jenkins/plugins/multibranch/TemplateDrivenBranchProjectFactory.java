/*
 * The MIT License
 *
 * Copyright (c) 2016, Matthew DeTullio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.mjdetullio.jenkins.plugins.multibranch;

import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Saveable;
import hudson.model.TopLevelItem;
import hudson.util.AtomicFileWriter;
import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.xml.XMLUtils;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Matthew DeTullio
 */
public abstract class TemplateDrivenBranchProjectFactory<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>>
        extends BranchProjectFactory<P, B> {

    private static final String CLASSNAME = TemplateDrivenBranchProjectFactory.class.getName();
    private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public Branch getBranch(@Nonnull P project) {
        return project.getProperty(BranchProjectProperty.class).getBranch();
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public P setBranch(@Nonnull P project, @Nonnull Branch branch) {
        BranchProjectProperty property = project.getProperty(BranchProjectProperty.class);

        BulkChange bc = new BulkChange(project);
        try {
            if (property == null) {
                project.addProperty(new BranchProjectProperty<P, B>(branch));
            } else {
                property.setBranch(branch);
            }
            project.setScm(branch.getScm());
            bc.commit();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to set BranchProjectProperty", e);
            bc.abort();
        }

        return project;
    }

    /**
     * Decorates projects by using {@link #updateByXml(P, Source)} and saving the configuration,
     * rather than only updating the project in memory.
     *
     * @param project the project to decorate
     * @return the project that was just decorated
     */
    @Override
    public P decorate(P project) {
        if (!isProject(project)) {
            return project;
        }

        if (!(getOwner() instanceof TemplateDrivenMultiBranchProject)) {
            throw new IllegalStateException(String.format("%s can only be used with %s.",
                    TemplateDrivenBranchProjectFactory.class.getSimpleName(),
                    TemplateDrivenMultiBranchProject.class.getSimpleName()));
        }

        TemplateDrivenMultiBranchProject<P, B> owner = (TemplateDrivenMultiBranchProject<P, B>) getOwner();

        Branch branch = getBranch(project);
        String displayName = project.getDisplayNameOrNull();
        boolean wasDisabled = project.isDisabled();

        BulkChange bc = new BulkChange(project);
        try {
            updateByXml(project, new StreamSource(owner.getTemplate().getConfigFile().readRaw()));

            // Restore settings managed by this plugin
            setBranch(project, branch);
            project.setDisplayName(displayName);
            project.setScm(branch.getScm());

            // Workarounds for JENKINS-21017
            project.setBuildDiscarder(owner.getTemplate().getBuildDiscarder());
            project.setCustomWorkspace(owner.getTemplate().getCustomWorkspace());

            if (!wasDisabled) {
                project.enable();
            }

            project = super.decorate(project);

            bc.commit();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to update project " + project.getName(), e);
        } finally {
            bc.abort();
        }

        return project;
    }

    /**
     * This is a mirror of {@link hudson.model.AbstractItem#updateByXml(Source)} without the
     * {@link hudson.model.listeners.SaveableListener#fireOnChange(Saveable, XmlFile)} trigger.
     *
     * @param project project to update by XML
     * @param source  source of XML
     * @throws IOException if error performing update
     */
    @SuppressWarnings("ThrowFromFinallyBlock")
    private void updateByXml(final P project, Source source) throws IOException {
        project.checkPermission(Item.CONFIGURE);
        XmlFile configXmlFile = project.getConfigFile();
        final AtomicFileWriter out = new AtomicFileWriter(configXmlFile.getFile());
        try {
            try {
                XMLUtils.safeTransform(source, new StreamResult(out));
                out.close();
            } catch (SAXException | TransformerException e) {
                throw new IOException("Failed to persist config.xml", e);
            }

            // try to reflect the changes by reloading
            Object o = new XmlFile(Items.XSTREAM, out.getTemporaryFile()).unmarshal(project);
            if (o != project) {
                // ensure that we've got the same job type. extending this code to support updating
                // to different job type requires destroying & creating a new job type
                throw new IOException("Expecting " + project.getClass() + " but got " + o.getClass() + " instead");
            }

            Items.whileUpdatingByXml(new NotReallyRoleSensitiveCallable<Void, IOException>() {
                @SuppressWarnings("unchecked")
                @Override
                public Void call() throws IOException {
                    project.onLoad(project.getParent(), project.getRootDir().getName());
                    return null;
                }
            });
            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();

            // if everything went well, commit this new version
            out.commit();
        } finally {
            out.abort();
        }
    }
}
