/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.folders.ui;



import java.net.URL;



import org.eclipse.jface.resource.ImageDescriptor;

import org.eclipse.jface.viewers.LabelProvider;

import org.eclipse.swt.graphics.Image;

import org.eclipse.ui.IMemento;

import org.eclipse.ui.navigator.ICommonContentExtensionSite;

import org.eclipse.ui.navigator.ICommonLabelProvider;

import org.osgi.framework.Bundle;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.folders.model.Cluster;



/**

 * Gives cluster folder nodes their text, icon and status-bar description.

 * <p>

 * It answers only for cluster nodes; for everything else it answers nothing, leaving EDT's own label

 * provider to render the real metadata objects.

 * </p>

 */

public class ClusterTreeLabels

    extends LabelProvider

    implements ICommonLabelProvider

{

    private static final String CLUSTER_ICON_PATH = "icons/cluster.png"; //$NON-NLS-1$



    private Image folderImage;



    @Override

    public void init(ICommonContentExtensionSite config)

    {

        folderImage = loadFolderImage();

    }



    @Override

    public String getText(Object element)

    {

        if (element instanceof ClusterNavigatorBridge)

        {

            return ((ClusterNavigatorBridge)element).getCluster().getName();

        }

        return null;

    }



    @Override

    public Image getImage(Object element)

    {

        return element instanceof ClusterNavigatorBridge ? folderImage : null;

    }



    @Override

    public String getDescription(Object element)

    {

        if (element instanceof ClusterNavigatorBridge)

        {

            Cluster cluster = ((ClusterNavigatorBridge)element).getCluster();

            String description = cluster.getDescription();

            if (description != null && !description.isEmpty())

            {

                return description;

            }

            return "Cluster: " + cluster.getFullPath(); //$NON-NLS-1$

        }

        return null;

    }



    @Override

    public void restoreState(IMemento memento)

    {

        // No state to restore.

    }



    @Override

    public void saveState(IMemento memento)

    {

        // No state to save.

    }



    @Override

    public void dispose()

    {

        if (folderImage != null && !folderImage.isDisposed())

        {

            folderImage.dispose();

        }

        folderImage = null;

        super.dispose();

    }



    /**

     * Loads the cluster folder icon from the bundle into a fresh image.

     *

     * @return the image, or <code>null</code> if the icon cannot be found

     */

    private static Image loadFolderImage()

    {

        Activator activator = Activator.getDefault();

        if (activator == null)

        {

            return null;

        }

        Bundle bundle = activator.getBundle();

        if (bundle == null)

        {

            return null;

        }

        URL url = bundle.getEntry(CLUSTER_ICON_PATH);

        if (url == null)

        {

            return null;

        }

        ImageDescriptor descriptor = ImageDescriptor.createFromURL(url);

        return descriptor == null ? null : descriptor.createImage();

    }

}

