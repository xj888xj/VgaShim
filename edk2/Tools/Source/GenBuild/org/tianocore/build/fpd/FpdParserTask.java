/** @file
  This file is ANT task FpdParserTask. 
 
  FpdParserTask is used to parse FPD (Framework Platform Description) and generate
  build.out.xml. It is for Package or Platform build use. 
 
Copyright (c) 2006, Intel Corporation
All rights reserved. This program and the accompanying materials
are licensed and made available under the terms and conditions of the BSD License
which accompanies this distribution.  The full text of the license may be found at
http://opensource.org/licenses/bsd-license.php

THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.
**/
package org.tianocore.build.fpd;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Property;
import org.apache.xmlbeans.XmlObject;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.tianocore.build.global.GlobalData;
import org.tianocore.build.global.OutputManager;
import org.tianocore.build.global.OverrideProcess;
import org.tianocore.build.global.SurfaceAreaQuery;
import org.tianocore.build.pcd.action.CollectPCDAction;
import org.tianocore.build.pcd.action.ActionMessage;
import org.tianocore.BuildOptionsDocument;
import org.tianocore.FrameworkPlatformDescriptionDocument;
import org.tianocore.ModuleSADocument;


/**
  <code>FpdParserTask</code> is an ANT task. The main function is parsing FPD
  XML file and generating its ANT build script for Platform or Package. 
  
  <p>The usage is (take NT32 Platform for example):</p>
  
  <pre>
    &lt;FPDParser fpdfilename="Build\Nt32.fpd" /&gt;
  </pre>
  
  <p>The task will initialize all information through parsing Framework Database, 
  SPD, Tool chain configuration files. </p>
  
  @since GenBuild 1.0
**/
public class FpdParserTask extends Task {

    ///
    /// FV dir: ${PLATFORM_DIR}/Build/FV
    ///
    public static final String FV_OUTPUT_DIR = "${PLATFORM_DIR}" + File.separatorChar + "Build" + File.separatorChar + "FV";

    private File fpdFilename;

    private File guiddatabase;

    ///
    /// Keep platform buildoption information
    ///
    public static XmlObject platformBuildOptions = null;

    ///
    /// Mapping from modules identification to out put file name
    ///
    private Map<FpdModuleIdentification, String> outfiles = new LinkedHashMap<FpdModuleIdentification, String>();

    ///
    /// Mapping from FV name to its modules
    ///
    private Map<String, Set<FpdModuleIdentification> > fvs = new HashMap<String, Set<FpdModuleIdentification> >();

    ///
    /// Mapping from sequence number to its modules
    ///
    private Map<String, Set<FpdModuleIdentification> > sequences = new HashMap<String, Set<FpdModuleIdentification> >();

    ///
    /// FpdParserTask can specify some ANT properties. 
    ///
    private Vector<Property> properties = new Vector<Property>();

    private String info = "====================================================================\n"
                    + "DO NOT EDIT \n"
                    + "File auto-generated by build utility\n"
                    + "\n"
                    + "Abstract:\n"
                    + "Auto-generated ANT build file for building of EFI Modules/Platforms\n"
                    + "=====================================================================";

    /**
      Public construct method. It is necessary for ANT task.
    **/
    public FpdParserTask () {
    }

    /**
      ANT task's entry method. The main steps is described as following: 
      
      <ul>
        <li>Initialize global information (Framework DB, SPD files and all MSA files 
        listed in SPD). This step will execute only once in whole build process;</li>
        <li>Parse specified FPD file; </li>
        <li>Generate FV.inf files; </li>
        <li>Generate build.out.xml file for Flatform or Package build; </li>
        <li>Collect PCD information. </li>
      </ul>
      
      @throws BuildException
                  Surface area is not valid. 
    **/
    public void execute() throws BuildException {
        OutputManager.update(getProject());
        //
        // Parse DB and SPDs files. Initialize Global Data
        //
        GlobalData.initInfo("Tools" + File.separatorChar + "Conf" + File.separatorChar + "FrameworkDatabase.db", getProject()
                        .getProperty("WORKSPACE_DIR"));
        //
        // Parse FPD file
        //
        parseFpdFile();
        //
        // Gen Fv.inf files
        //
        genFvInfFiles();
        //
        // Gen build.xml
        //
        genBuildFile();
        //
        // Collect PCD information 
        // 
        collectPCDInformation (); 
    }
    
    /**
      Generate Fv.inf files. The Fv.inf file is composed with four 
      parts: Options, Attributes, Components and Files. The Fv.inf files 
      will be under ${PLATFOMR_DIR}\Build\Fv.
      
      @throws BuildException
              File write FV.inf files error. 
    **/
    private void genFvInfFiles() throws BuildException{
        String[] validFv = SurfaceAreaQuery.getFpdValidImageNames();
        for (int i = 0; i < validFv.length; i++) {
            getProject().setProperty("FV_FILENAME", validFv[i].toUpperCase());
            //
            // Get all global variables from FPD and set them to properties
            //
            String[][] globalVariables = SurfaceAreaQuery
                            .getFpdGlobalVariable();
            for (int j = 0; j < globalVariables.length; j++) {
                getProject().setProperty(globalVariables[j][0],
                                globalVariables[j][1]);
            }

            File fvFile = new File(getProject().replaceProperties(
                            FV_OUTPUT_DIR + File.separatorChar + validFv[i].toUpperCase()
                                            + ".inf"));
            fvFile.getParentFile().mkdirs();

            try {
                FileWriter fw = new FileWriter(fvFile);
                BufferedWriter bw = new BufferedWriter(fw);
                //
                // Options
                //
                String[][] options = SurfaceAreaQuery.getFpdOptions(validFv[i]);
                if (options.length > 0) {
                    bw.write("[options]");
                    bw.newLine();
                    for (int j = 0; j < options.length; j++) {
                        StringBuffer str = new StringBuffer(100);
                        str.append(options[j][0]);
                        while (str.length() < 40) {
                            str.append(' ');
                        }
                        str.append("=  ");
                        str.append(options[j][1]);
                        bw.write(getProject().replaceProperties(str.toString()));
                        bw.newLine();
                    }
                    bw.newLine();
                }
                //
                // Attributes;
                //
                String[][] attributes = SurfaceAreaQuery
                                .getFpdAttributes(validFv[i]);
                if (attributes.length > 0) {
                    bw.write("[attributes]");
                    bw.newLine();
                    for (int j = 0; j < attributes.length; j++) {
                        StringBuffer str = new StringBuffer(100);
                        str.append(attributes[j][0]);
                        while (str.length() < 40) {
                            str.append(' ');
                        }
                        str.append("=  ");
                        str.append(attributes[j][1]);
                        bw
                                        .write(getProject().replaceProperties(
                                                        str.toString()));
                        bw.newLine();
                    }
                    bw.newLine();
                }
                //
                // Components
                //
                String[][] components = SurfaceAreaQuery
                                .getFpdComponents(validFv[i]);
                if (components.length > 0) {
                    bw.write("[components]");
                    bw.newLine();
                    for (int j = 0; j < components.length; j++) {
                        StringBuffer str = new StringBuffer(100);
                        str.append(components[j][0]);
                        while (str.length() < 40) {
                            str.append(' ');
                        }
                        str.append("=  ");
                        str.append(components[j][1]);
                        bw
                                        .write(getProject().replaceProperties(
                                                        str.toString()));
                        bw.newLine();
                    }
                    bw.newLine();
                }
                //
                // Files
                //
                Set<FpdModuleIdentification> filesSet = fvs.get(validFv[i].toUpperCase());
                if (filesSet != null) {
                    FpdModuleIdentification[] files = filesSet.toArray(new FpdModuleIdentification[filesSet
                                    .size()]);
                    bw.write("[files]");
                    bw.newLine();
                    for (int j = 0; j < files.length; j++) {
                        String str = outfiles.get(files[j]);
                        bw.write(getProject().replaceProperties(
                                        "EFI_FILE_NAME = " + str));
                        bw.newLine();
                    }
                }
                bw.flush();
                bw.close();
                fw.close();
            } catch (Exception e) {
                throw new BuildException("Generate Fv.inf file failed. \n" + e.getMessage());
            }
        }
    }

    /**
      Parse FPD file. 
    
      @throws BuildException
              FPD file is not valid. 
    **/
    private void parseFpdFile() throws BuildException {
        try {
            FrameworkPlatformDescriptionDocument doc = (FrameworkPlatformDescriptionDocument) XmlObject.Factory
                            .parse(fpdFilename);
            if ( ! doc.validate() ){
                throw new BuildException("FPD file is invalid.");
            }
            platformBuildOptions = doc.getFrameworkPlatformDescription()
                            .getBuildOptions();
            HashMap<String, XmlObject> map = new HashMap<String, XmlObject>();
            map.put("FrameworkPlatformDescription", doc);
            SurfaceAreaQuery.setDoc(map);
            //
            // Parse all list modules SA
            //
            parseModuleSAFiles();
            SurfaceAreaQuery.setDoc(map);
        } catch (Exception e) {
            throw new BuildException("Load FPD file [" + fpdFilename.getPath()
                            + "] error. \n" + e.getMessage());
        }
    }

    /**
      Parse all modules listed in FPD file. 
    **/
    private void parseModuleSAFiles() {
        ModuleSADocument.ModuleSA[] moduleSAs = SurfaceAreaQuery
                        .getFpdModules();
        //
        // For every Module lists in FPD file.
        //
        for (int i = 0; i < moduleSAs.length; i++) {
            String defaultFv = "NULL";
            String defaultArch = "IA32";
            String baseName = moduleSAs[i].getModuleName();
            if (baseName == null) {
                System.out.println("Warning: Module Name is not specified.");
                continue;
            }
            String fvBinding = moduleSAs[i].getFvBinding();
            //
            // If the module do not specify any FvBinding, use the default value.
            // Else update the default FvBinding value to this value.
            //
            if (fvBinding == null) {
                fvBinding = defaultFv;
            }
            else {
                defaultFv = fvBinding;
            }
            String arch;
            //
            // If the module do not specify any Arch, use the default value.
            // Else update the default Arch value to this value.
            //
            if (moduleSAs[i].getArch() == null ){
                arch = defaultArch;
            }
            else {
                arch = moduleSAs[i].getArch().toString();
                defaultArch = arch;
            }
            Map<String, XmlObject> msaMap = GlobalData.getNativeMsa(baseName);
            Map<String, XmlObject> mbdMap = GlobalData.getNativeMbd(baseName);
            Map<String, XmlObject> fpdMap = new HashMap<String, XmlObject>();
            Map<String, XmlObject> map = new HashMap<String, XmlObject>();
            //
            // Whether the Module SA has parsed before or not
            //
            if (!GlobalData.isModuleParsed(baseName)) {
                OverrideProcess op = new OverrideProcess();
                //
                // using overriding rules
                // Here we can also put platform Build override
                //
                map = op.override(mbdMap, msaMap);
                fpdMap = getPlatformOverrideInfo(moduleSAs[i]);
                XmlObject buildOption = (XmlObject)fpdMap.get("BuildOptions");
                buildOption = (XmlObject)fpdMap.get("PackageDependencies");
                buildOption = (XmlObject)fpdMap.get("BuildOptions");
                buildOption = op.override(buildOption, platformBuildOptions);
                fpdMap.put("BuildOptions", ((BuildOptionsDocument)buildOption).getBuildOptions());
                Map<String, XmlObject> overrideMap = op.override(fpdMap, OverrideProcess.deal(map));
                GlobalData.registerModule(baseName, overrideMap);
            } else {
                map = GlobalData.getDoc(baseName);
            }
            SurfaceAreaQuery.setDoc(map);
            String guid = SurfaceAreaQuery.getModuleGuid();
            String componentType = SurfaceAreaQuery.getComponentType();
            FpdModuleIdentification moduleId = new FpdModuleIdentification(baseName, guid, arch);
            updateFvs(fvBinding, moduleId);
            outfiles.put(moduleId, "${PLATFORM_DIR}" + File.separatorChar + "Build" + File.separatorChar 
                            + "${TARGET}" + File.separatorChar + arch
                            + File.separatorChar + guid + "-" + baseName
                            + getSuffix(componentType));
        }
    }

    /**
      Add the current module to corresponding FV. 
    
      @param fvName current FV name
      @param moduleName current module identification
    **/
    private void updateFvs(String fvName, FpdModuleIdentification moduleName) {
        String upcaseFvName = fvName.toUpperCase();
        if (fvs.containsKey(upcaseFvName)) {
            Set<FpdModuleIdentification> set = fvs.get(upcaseFvName);
            set.add(moduleName);
        } else {
            Set<FpdModuleIdentification> set = new LinkedHashSet<FpdModuleIdentification>();
            set.add(moduleName);
            fvs.put(upcaseFvName, set);
        }
    }

    /**
      Get the suffix based on component type. Current relationship are listed:  
      
      <pre>
        <b>ComponentType</b>   <b>Suffix</b>
           APPLICATION          .APP
           SEC                  .SEC
           PEI_CORE             .PEI
           PE32_PEIM            .PEI
           RELOCATABLE_PEIM     .PEI
           PIC_PEIM             .PEI
           COMBINED_PEIM_DRIVER .PEI
           TE_PEIM              .PEI
           LOGO                 .FFS
           others               .DXE
      </pre>
    
      @param componentType component type
      @return
      @throws BuildException
              If component type is null
    **/
    public static String getSuffix(String componentType) throws BuildException{
        if (componentType == null) {
            throw new BuildException("Component type is not specified.");
        }
        String str = ".DXE";
        if (componentType.equalsIgnoreCase("APPLICATION")) {
            str = ".APP";
        } else if (componentType.equalsIgnoreCase("SEC")) {
            str = ".SEC";
        } else if (componentType.equalsIgnoreCase("PEI_CORE")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("PE32_PEIM")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("RELOCATABLE_PEIM")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("PIC_PEIM")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("COMBINED_PEIM_DRIVER")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("TE_PEIM")) {
            str = ".PEI";
        } else if (componentType.equalsIgnoreCase("LOGO")) {
            str = ".FFS";
        }
        return str;
    }

    /**
      Parse module surface are info described in FPD file and put them into map. 
      
      @param sa module surface area info descibed in FPD file
      @return map list with top level elements
    **/
    private Map<String, XmlObject> getPlatformOverrideInfo(
                    ModuleSADocument.ModuleSA sa) {
        Map<String, XmlObject> map = new HashMap<String, XmlObject>();
        map.put("SourceFiles", sa.getSourceFiles());
        map.put("Includes", sa.getIncludes());
        map.put("PackageDependencies", null);
        map.put("Libraries", sa.getLibraries());
        map.put("Protocols", sa.getProtocols());
        map.put("Events", sa.getEvents());
        map.put("Hobs", sa.getHobs());
        map.put("PPIs", sa.getPPIs());
        map.put("Variables", sa.getVariables());
        map.put("BootModes", sa.getBootModes());
        map.put("SystemTables", sa.getSystemTables());
        map.put("DataHubs", sa.getDataHubs());
        map.put("Formsets", sa.getFormsets());
        map.put("Guids", sa.getGuids());
        map.put("Externs", sa.getExterns());
        map.put("BuildOptions", sa.getBuildOptions());//platformBuildOptions);
        return map;
    }

    /**
      Generate build.out.xml file.
      
      @throws BuildException
              build.out.xml XML document create error
    **/
    private void genBuildFile() throws BuildException {
        DocumentBuilderFactory domfac = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder dombuilder = domfac.newDocumentBuilder();
            Document document = dombuilder.newDocument();
            Comment rootComment = document.createComment(info);
            //
            // create root element and its attributes
            //
            Element root = document.createElement("project");
            root.setAttribute("name", getProject().getProperty("PLATFORM"));
            root.setAttribute("default", "main");
            root.setAttribute("basedir", ".");
            //
            // element for External ANT tasks
            //
            root.appendChild(document.createComment("Apply external ANT tasks"));
            Element ele = document.createElement("taskdef");
            ele.setAttribute("resource", "GenBuild.tasks");
            root.appendChild(ele);

            ele = document.createElement("taskdef");
            ele.setAttribute("resource", "frameworktasks.tasks");
            root.appendChild(ele);

            ele = document.createElement("property");
            ele.setAttribute("environment", "env");
            root.appendChild(ele);
            //
            // Default Target
            //
            root.appendChild(document.createComment("Default target"));
            ele = document.createElement("target");
            ele.setAttribute("name", "main");
            ele.setAttribute("depends", "modules, fvs");
            root.appendChild(ele);
            //
            // Modules Target
            //
            root.appendChild(document.createComment("Modules target"));
            ele = document.createElement("target");
            ele.setAttribute("name", "modules");

            Set set = outfiles.keySet();
            Iterator iter = set.iterator();
            while (iter.hasNext()) {
                FpdModuleIdentification moduleId = (FpdModuleIdentification) iter.next();
                String baseName = moduleId.getBaseName();
                Element moduleEle = document.createElement("ant");
                moduleEle.setAttribute("antfile", GlobalData
                                .getModulePath(baseName)
                                + File.separatorChar + "build.xml");
                moduleEle.setAttribute("target", baseName);
                //
                // ARCH
                //
                Element property = document.createElement("property");
                property.setAttribute("name", "ARCH");
                property.setAttribute("value", moduleId.getArch());
                moduleEle.appendChild(property);
                //
                // PACKAGE_DIR
                //
                property = document.createElement("property");
                property.setAttribute("name", "PACKAGE_DIR");
                property.setAttribute("value", "${WORKSPACE_DIR}" + File.separatorChar
                                + GlobalData.getPackagePathForModule(baseName));
                moduleEle.appendChild(property);
                //
                // PACKAGE
                //
                property = document.createElement("property");
                property.setAttribute("name", "PACKAGE");
                property.setAttribute("value", GlobalData
                                .getPackageNameForModule(baseName));
                moduleEle.appendChild(property);
                ele.appendChild(moduleEle);
            }
            root.appendChild(ele);
            //
            // FVS Target
            //
            root.appendChild(document.createComment("FVs target"));
            ele = document.createElement("target");
            ele.setAttribute("name", "fvs");

            String[] validFv = SurfaceAreaQuery.getFpdValidImageNames();
            for (int i = 0; i < validFv.length; i++) {
                String inputFile = FV_OUTPUT_DIR + "" + File.separatorChar
                                + validFv[i].toUpperCase() + ".inf";
                Element fvEle = document.createElement("genfvimage");
                fvEle.setAttribute("infFile", inputFile);
                ele.appendChild(fvEle);
                Element moveEle = document.createElement("move");
                moveEle.setAttribute("file", validFv[i].toUpperCase() + ".fv");
                moveEle.setAttribute("todir", FV_OUTPUT_DIR);
                ele.appendChild(moveEle);
            }
            root.appendChild(ele);
            
            boolean isUnified = false;
            BuildOptionsDocument.BuildOptions buildOptions = (BuildOptionsDocument.BuildOptions)platformBuildOptions;
            if (buildOptions.getOutputDirectory() != null){
                if (buildOptions.getOutputDirectory().getIntermediateDirectories() != null){
                    if (buildOptions.getOutputDirectory().getIntermediateDirectories().toString().equalsIgnoreCase("UNIFIED")){
                        isUnified = true;
                    }
                }
            }
            //
            // Clean Target
            //
            root.appendChild(document.createComment("Clean target"));
            ele = document.createElement("target");
            ele.setAttribute("name", "clean");
            
            if (isUnified) {
                Element cleanEle = document.createElement("delete");
                cleanEle.setAttribute("includeemptydirs", "true");
                Element filesetEle = document.createElement("fileset");
                filesetEle.setAttribute("dir", getProject().getProperty("PLATFORM_DIR") + File.separatorChar + "Build" + File.separatorChar + "${TARGET}");
                filesetEle.setAttribute("includes", "**/OUTPUT/**");
                cleanEle.appendChild(filesetEle);
                ele.appendChild(cleanEle);
            }
            else {
                set = outfiles.keySet();
                iter = set.iterator();
                while (iter.hasNext()) {
                    FpdModuleIdentification moduleId = (FpdModuleIdentification) iter.next();
                    String baseName = moduleId.getBaseName();
    
                    Element ifEle = document.createElement("if");
                    Element availableEle = document.createElement("available");
                    availableEle.setAttribute("file", GlobalData
                                    .getModulePath(baseName)
                                    + File.separatorChar + "build.xml");
                    ifEle.appendChild(availableEle);
                    Element elseEle = document.createElement("then");
    
                    Element moduleEle = document.createElement("ant");
                    moduleEle.setAttribute("antfile", GlobalData
                                    .getModulePath(baseName)
                                    + File.separatorChar + "build.xml");
                    moduleEle.setAttribute("target", baseName + "_clean");
                    //
                    // ARCH
                    //
                    Element property = document.createElement("property");
                    property.setAttribute("name", "ARCH");
                    property.setAttribute("value", moduleId.getArch());
                    moduleEle.appendChild(property);
                    //
                    // PACKAGE_DIR
                    //
                    property = document.createElement("property");
                    property.setAttribute("name", "PACKAGE_DIR");
                    property.setAttribute("value", "${WORKSPACE_DIR}" + File.separatorChar
                                    + GlobalData.getPackagePathForModule(baseName));
                    moduleEle.appendChild(property);
                    //
                    // PACKAGE
                    //
                    property = document.createElement("property");
                    property.setAttribute("name", "PACKAGE");
                    property.setAttribute("value", GlobalData
                                    .getPackageNameForModule(baseName));
                    moduleEle.appendChild(property);
                    elseEle.appendChild(moduleEle);
                    ifEle.appendChild(elseEle);
                    ele.appendChild(ifEle);
                }
            }
            root.appendChild(ele);
            //
            // Deep Clean Target
            //
            root.appendChild(document.createComment("Clean All target"));
            ele = document.createElement("target");
            ele.setAttribute("name", "cleanall");

            if (isUnified) {
                Element cleanAllEle = document.createElement("delete");
                cleanAllEle.setAttribute("dir", getProject().getProperty("PLATFORM_DIR") + File.separatorChar + "Build" + File.separatorChar + "${TARGET}");
                ele.appendChild(cleanAllEle);
            }
            else {
                set = outfiles.keySet();
                iter = set.iterator();
                while (iter.hasNext()) {
                    FpdModuleIdentification moduleId = (FpdModuleIdentification) iter.next();
                    String baseName = moduleId.getBaseName();
    
                    Element ifEle = document.createElement("if");
                    Element availableEle = document.createElement("available");
                    availableEle.setAttribute("file", GlobalData
                                    .getModulePath(baseName)
                                    + File.separatorChar + "build.xml");
                    ifEle.appendChild(availableEle);
                    Element elseEle = document.createElement("then");
    
                    Element moduleEle = document.createElement("ant");
                    moduleEle.setAttribute("antfile", GlobalData
                                    .getModulePath(baseName)
                                    + File.separatorChar + "build.xml");
                    moduleEle.setAttribute("target", baseName + "_cleanall");
                    //
                    // ARCH
                    //
                    Element property = document.createElement("property");
                    property.setAttribute("name", "ARCH");
                    property.setAttribute("value", moduleId.getArch());
                    moduleEle.appendChild(property);
                    //
                    // PACKAGE_DIR
                    //
                    property = document.createElement("property");
                    property.setAttribute("name", "PACKAGE_DIR");
                    property.setAttribute("value", "${WORKSPACE_DIR}" + File.separatorChar
                                    + GlobalData.getPackagePathForModule(baseName));
                    moduleEle.appendChild(property);
                    //
                    // PACKAGE
                    //
                    property = document.createElement("property");
                    property.setAttribute("name", "PACKAGE");
                    property.setAttribute("value", GlobalData
                                    .getPackageNameForModule(baseName));
                    moduleEle.appendChild(property);
                    elseEle.appendChild(moduleEle);
                    ifEle.appendChild(elseEle);
                    ele.appendChild(ifEle);
                }
            }
            root.appendChild(ele);
            
            document.appendChild(rootComment);
            document.appendChild(root);
            //
            // Prepare the DOM document for writing
            //
            Source source = new DOMSource(document);
            //
            // Prepare the output file
            //
            File file = new File(getProject().getProperty("PLATFORM_DIR")
                            + File.separatorChar + "build.out.xml");
            //
            // generate all directory path
            //
            (new File(file.getParent())).mkdirs();
            Result result = new StreamResult(file);
            //
            // Write the DOM document to the file
            //
            Transformer xformer = TransformerFactory.newInstance()
                            .newTransformer();
            xformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            xformer.setOutputProperty(OutputKeys.INDENT, "yes");
            xformer.transform(source, result);
        } catch (Exception ex) {
            throw new BuildException("Generate build.out.xml failed. \n" + ex.getMessage());
        }
    }

    /**
      Add a property. 
      
      @param p property
    **/
    public void addProperty(Property p) {
        properties.addElement(p);
    }

    /**
      Get FPD file name.
       
      @return FPD file name.
    **/
    public File getFpdFilename() {
        return fpdFilename;
    }

    /**
      Set FPD file name.
      
      @param fpdFilename FPD file name
    **/
    public void setFpdFilename(File fpdFilename) {
        this.fpdFilename = fpdFilename;
    }

    public File getGuiddatabase() {
        return guiddatabase;
    }

    public void setGuiddatabase(File guiddatabase) {
        this.guiddatabase = guiddatabase;
    }

    public void collectPCDInformation() {
        String           exceptionString = null;
        CollectPCDAction collectAction   = new CollectPCDAction ();
        //
        // Collect all PCD information from FPD to MSA, and get help information from SPD.
        // These all information will be stored into memory database for future usage such 
        // as autogen.
        //
        try {
            collectAction.perform (getProject().getProperty("WORKSPACE_DIR"),
                                   fpdFilename.getPath(),
                                   ActionMessage.MAX_MESSAGE_LEVEL
                                   );
        } catch (Exception exp) {
            exceptionString = exp.getMessage();
            if (exceptionString == null) {
                exceptionString = "[Internal Error]Pcd tools catch a internel errors, Please report this bug into TianoCore or send email to Wang, scott or Lu, ken!";
            }
            throw new BuildException (String.format("Fail to do PCD preprocess from FPD file: %s", exceptionString));
        }
    }
}
