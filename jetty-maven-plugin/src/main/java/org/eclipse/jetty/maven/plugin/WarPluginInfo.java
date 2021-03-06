//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.maven.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;



/**
 * WarPluginInfo
 *
 * Information about the maven-war-plugin contained in the pom
 */
public class WarPluginInfo
{
    private MavenProject _project;
    private Plugin _plugin;
    private List<String> _dependentMavenWarIncludes;
    private List<String> _dependentMavenWarExcludes;
    private List<OverlayConfig> _overlayConfigs;
    private Resource[] _webResources;



    /**
     * @param project
     */
    public WarPluginInfo (MavenProject project)
    {
        _project = project;
    }

    
    
    
    /**
     * Find the maven-war-plugin, if one is configured
     * @return
     */
    public Plugin getPlugin()
    {
        if (_plugin == null)
        {
            List plugins = _project.getBuildPlugins();
            if (plugins == null)
                return null;


            Iterator itor = plugins.iterator();
            while (itor.hasNext() && _plugin==null)
            {
                Plugin plugin = (Plugin)itor.next();
                if ("maven-war-plugin".equals(plugin.getArtifactId()))
                    _plugin = plugin;
            }
        }
        return _plugin;
    }

    
    

    /**
     * Get value of dependentWarIncludes for maven-war-plugin
     * @return
     */
    public List<String> getDependentMavenWarIncludes()
    {
        if (_dependentMavenWarIncludes == null)
        {
            getPlugin();

            if (_plugin == null)
                return null;

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return null;

            node = node.getChild("dependentWarIncludes");
            if (node == null)
                return null;
            String val = node.getValue();
            _dependentMavenWarIncludes = Arrays.asList(val.split(",")); 
        }
        return _dependentMavenWarIncludes;
    }


    
    
    /**
     * Get value of dependentWarExcludes for maven-war-plugin
     * @return
     */
    public List<String> getDependentMavenWarExcludes()
    {
        if (_dependentMavenWarExcludes == null)
        {
            getPlugin();

            if (_plugin == null)
                return null;

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return null;

            node = node.getChild("dependentWarExcludes");
            if (node == null)
                return null;
            String val = node.getValue();
            _dependentMavenWarExcludes = Arrays.asList(val.split(","));
        }
        return _dependentMavenWarExcludes;
    }

    
    
    
    /**
     * Get config for any overlays that have been declared for the maven-war-plugin.
     * 
     * @return
     */
    public List<OverlayConfig> getMavenWarOverlayConfigs ()
    {
        if (_overlayConfigs == null)
        {
            getPlugin();

            if (_plugin == null)
                return Collections.emptyList();

            getDependentMavenWarIncludes();
            getDependentMavenWarExcludes();

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return Collections.emptyList();

            node = node.getChild("overlays");
            if (node == null)
                return Collections.emptyList();

            Xpp3Dom[] nodes = node.getChildren("overlay");
            if (nodes == null)
                return Collections.emptyList();

            _overlayConfigs = new ArrayList<OverlayConfig>();
            for (int i=0;i<nodes.length;i++)
            {
                OverlayConfig overlayConfig = new OverlayConfig(nodes[i], _dependentMavenWarIncludes, _dependentMavenWarExcludes);
                _overlayConfigs.add(overlayConfig);
            }
        }

        return _overlayConfigs;
    }

    public Resource[] getWarWebResources() {
      if ( _webResources == null )
      {
        getPlugin();

        if (_plugin == null)
          return null;

        Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
        if (node == null || (node = node.getChild("webResources")) == null )
          return null;

        final Xpp3Dom[] resourceNodes = node.getChildren("resource");
        List resources = new ArrayList(resourceNodes.length);

        for (int i = 0; i < resourceNodes.length; ++i )
        {
          Xpp3Dom resourceNode = resourceNodes[i];
          Resource resource = new Resource();
          Xpp3Dom helperNode;

          helperNode = resourceNode.getChild("targetPath");
          if (helperNode != null)
            resource.setTargetPath(helperNode.getValue());

          helperNode = resourceNode.getChild("directory");
          if (helperNode != null)
            resource.setDirectory(helperNode.getValue());

          helperNode = resourceNode.getChild("includes");
          if (helperNode != null)
            resource.setIncludes(processPatterns("include", helperNode));

          helperNode = resourceNode.getChild("filtering");
          if ( helperNode != null )
            resource.setFiltering(helperNode.getValue());

          helperNode = resourceNode.getChild("excludes");
          if (helperNode != null)
            resource.setExcludes(processPatterns("exclude", helperNode));

          resources.add(resource);
        }
        _webResources = (Resource[]) resources.toArray(new Resource[resources.size()]);
      }
      return _webResources;
    }


    /**
     * @return the xml as a string
     */
    public String getMavenWarOverlayConfigAsString ()
    {
        getPlugin();

        if (_plugin == null)
            return "";
        
        Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
        if (node == null)
            return "";
        return node.toString();
    }

    private List processPatterns(String nodeName, final Xpp3Dom includesNode)
    {
        Xpp3Dom[] patternNodes = includesNode.getChildren(nodeName);
        List patterns = new ArrayList(patternNodes.length);
        for (int i = 0; i < patternNodes.length; i++ )
        {
            Xpp3Dom pattern = patternNodes[i];
            patterns.add(pattern.getValue());
        }
        return patterns;
    }


}
