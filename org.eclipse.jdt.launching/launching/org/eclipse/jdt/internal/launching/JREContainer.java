/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.launching;


import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallChangedListener;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.LibraryLocation;
import org.eclipse.jdt.launching.PropertyChangeEvent;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.osgi.util.NLS;

/** 
 * JRE Container - resolves a classpath container variable to a JRE
 */
public class JREContainer implements IClasspathContainer {

	/**
	 * Corresponding JRE
	 */
	private IVMInstall fVMInstall = null;
	
	/**
	 * Container path used to resolve to this JRE
	 */
	private IPath fPath = null;
	
	/**
	 * The project this container is for
	 */
	private IJavaProject fProject = null;
	
	/**
	 * Cache of classpath entries per VM install. Cleared when a VM changes.
	 */
	private static Map<IVMInstall, IClasspathEntry[]> fgClasspathEntries = new HashMap<IVMInstall, IClasspathEntry[]>(10);
	
	/**
	 * Variable to return an empty array of <code>IAccessRule</code>s
	 */
	private static IAccessRule[] EMPTY_RULES = new IAccessRule[0];
	
	/**
	 * Map of {IVMInstall -> Map of {{IExeuctionEnvironment, IAccessRule[][]} -> {IClasspathEntry[]}} 
	 */
	private static Map<RuleKey, RuleEntry> fgClasspathEntriesWithRules = new HashMap<RuleKey, RuleEntry>(10);
	
	/**
	 * A single key entry for the cache of access rules and classpath entries
	 * A rule key is made up of an <code>IVMInstall</code> and an execution environment id
	 * @since 3.3
	 */
	static class RuleKey {
		private String fEnvironmentId = null;
		private IVMInstall fInstall = null;
		
		/**
		 * Constructor
		 * @param install the VM
		 * @param environmentId the environment
		 */
		public RuleKey(IVMInstall install, String environmentId) {
			fInstall = install;
			fEnvironmentId = environmentId;
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (obj instanceof RuleKey) {
				RuleKey key = (RuleKey) obj;
				return fEnvironmentId.equals(key.fEnvironmentId) && fInstall.equals(key.fInstall);
			}
			return false;
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			return fEnvironmentId.hashCode() + fInstall.hashCode();
		}
	}
	
	/**
	 * Holds an entry for the cache of access rules/classpath entries.
	 * An entry is made up of an array of classpath entries and the collection of access rules. 
	 * @since 3.3
	 */
	static class RuleEntry {
		private IAccessRule[][] fRules = null;
		private IClasspathEntry[] fEntries = null;
		
		/**
		 * Constructor
		 * @param rules the rules
		 * @param entries the entries
		 */
		public RuleEntry(IAccessRule[][] rules, IClasspathEntry[] entries) {
			fRules = rules;
			fEntries = entries;
		}
		
		/**
		 * Returns the collection of classpath entries for this RuleEntry
		 * @return the cached array of classpath entries
		 */
		public IClasspathEntry[] getClasspathEntries() {
			return fEntries;
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			IAccessRule[][] rules = null;
			if(obj instanceof RuleEntry) {
				rules = ((RuleEntry)obj).fRules;
			}
			if(obj instanceof IAccessRule[][]) {
				rules = (IAccessRule[][]) obj;
			}
			if(rules != null) {
				if (fRules == rules) {
					return true;
				}
				if (fRules.length == rules.length) {
					boolean equal = true;
					IAccessRule[] originalrule, comparerule = null; 
					for (int i = 0; i < fRules.length; i++) {
						originalrule = fRules[i];
						comparerule = rules[i];
						equal &= (originalrule == comparerule);
						if(!equal) {
							if (originalrule.length == comparerule.length) {
								for (int j = 0; j < originalrule.length; j++) {
									if (!originalrule[j].equals(comparerule[j])) {
										return false;
									}
								}
								return true;
							} 
							return false;
						}
					}
					return equal;
				}
			}
			return false;
		}
	}
	
	/**
	 * Add a VM changed listener to clear cached values when a VM changes or is removed
	 */
	static {
		IVMInstallChangedListener listener = new IVMInstallChangedListener() {

			/* (non-Javadoc)
			 * @see org.eclipse.jdt.launching.IVMInstallChangedListener#defaultVMInstallChanged(org.eclipse.jdt.launching.IVMInstall, org.eclipse.jdt.launching.IVMInstall)
			 */
			public void defaultVMInstallChanged(IVMInstall previous, IVMInstall current) {}
			
			/* (non-Javadoc)
			 * @see org.eclipse.jdt.launching.IVMInstallChangedListener#vmAdded(org.eclipse.jdt.launching.IVMInstall)
			 */
			public void vmAdded(IVMInstall newVm) {}

			/* (non-Javadoc)
			 * @see org.eclipse.jdt.launching.IVMInstallChangedListener#vmChanged(org.eclipse.jdt.launching.PropertyChangeEvent)
			 */
			public void vmChanged(PropertyChangeEvent event) {
				if (event.getSource() != null) {
					fgClasspathEntries.remove(event.getSource());
					removeRuleEntry(event.getSource());
				}
			}

			/* (non-Javadoc)
			 * @see org.eclipse.jdt.launching.IVMInstallChangedListener#vmRemoved(org.eclipse.jdt.launching.IVMInstall)
			 */
			public void vmRemoved(IVMInstall removedVm) {
				fgClasspathEntries.remove(removedVm);
				removeRuleEntry(removedVm);
			}
			
			/**
			 * Removes all occurrences of the given VM found as part key members in the current
			 * cache for classpath entries
			 * @param obj an object which should be castable to IVMInstall
			 */
			private void removeRuleEntry(Object obj) {
				if(obj instanceof IVMInstall) {
					IVMInstall install = (IVMInstall) obj;
					RuleKey key = null;
					ArrayList<RuleKey> list = new ArrayList<RuleKey>();
					for(Iterator<RuleKey> iter = fgClasspathEntriesWithRules.keySet().iterator(); iter.hasNext();) {
						key  = iter.next();
						if(key.fInstall.equals(install)) {
							list.add(key);
						}
					}
					for(int i = 0; i < list.size(); i++) {
						fgClasspathEntriesWithRules.remove(list.get(i));
					}
				}
			}
		};
		JavaRuntime.addVMInstallChangedListener(listener);
	}
	
	/**
	 * Returns the classpath entries associated with the given VM
	 * in the context of the given path and project.
	 * 
	 * @param vm the VM
	 * @param containerPath the container path resolution is for
	 * @param project project the resolution is for
	 * @return classpath entries
	 */
	private static IClasspathEntry[] getClasspathEntries(IVMInstall vm, IPath containerPath, IJavaProject project) {
		String id = JavaRuntime.getExecutionEnvironmentId(containerPath);
		IClasspathEntry[] entries = null;
		if (id == null) {
			// cache classpath entries per JRE when not bound to an EE
			entries = fgClasspathEntries.get(vm);
			if (entries == null) {
				entries = computeClasspathEntries(vm, project, id);
				fgClasspathEntries.put(vm, entries);
			}
		} else {
			if (LaunchingPlugin.DEBUG_JRE_CONTAINER) {
				System.out.println("\tEE:\t" + id); //$NON-NLS-1$
			}
			// dynamically compute entries when bound to an EE
			entries = computeClasspathEntries(vm, project, id);
		}
		return entries;
	}
	
	/**
	 * Computes the classpath entries associated with a VM - one entry per library
	 * in the context of the given path and project.
	 * 
	 * @param vm the VM
	 * @param project the project the resolution is for
	 * @param environmentId execution environment the resolution is for, or <code>null</code>
	 * @return classpath entries
	 */
	private static IClasspathEntry[] computeClasspathEntries(IVMInstall vm, IJavaProject project, String environmentId) {
		LibraryLocation[] libs = vm.getLibraryLocations();
		boolean overrideJavaDoc = false;
		if (libs == null) {
			libs = JavaRuntime.getLibraryLocations(vm);
			overrideJavaDoc = true;
		}
		IAccessRule[][] rules = null;
		if (environmentId != null) {
			// compute access rules for execution environment
			IExecutionEnvironment environment = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(environmentId);
			if (environment != null) {
				rules = environment.getAccessRules(vm, libs, project);
			}
		}
		RuleKey key = null;
		if (vm != null && rules != null && environmentId != null) {
			key = new RuleKey(vm, environmentId);
			RuleEntry entry = fgClasspathEntriesWithRules.get(key);
			if(entry != null && entry.equals(rules)) {
				return entry.getClasspathEntries();
			}
		}
		List<IClasspathEntry> entries = new ArrayList<IClasspathEntry>(libs.length);
		for (int i = 0; i < libs.length; i++) {
			if (!libs[i].getSystemLibraryPath().isEmpty()) {
				IPath sourcePath = libs[i].getSystemLibrarySourcePath();
				if (sourcePath.isEmpty()) {
					sourcePath = null;
				}
				IPath rootPath = libs[i].getPackageRootPath();
				if (rootPath.isEmpty()) {
					rootPath = null;
				}
				URL javadocLocation = libs[i].getJavadocLocation();
				if (overrideJavaDoc && javadocLocation == null) {
					javadocLocation = vm.getJavadocLocation();
				}
				IClasspathAttribute[] attributes = null;
				if (javadocLocation == null) {
					attributes = new IClasspathAttribute[0];
				} else {
					attributes = new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, javadocLocation.toExternalForm())};
				}
				IAccessRule[] libRules = null;
				if (rules != null) {
					libRules = rules[i];
				} else {
					libRules = EMPTY_RULES;
				}
				entries.add(JavaCore.newLibraryEntry(libs[i].getSystemLibraryPath(), sourcePath, rootPath, libRules, attributes, false));
			}
		}
		IClasspathEntry[] cpEntries = entries.toArray(new IClasspathEntry[entries.size()]);
		if (key != null && rules != null) {
			fgClasspathEntriesWithRules.put(key, new RuleEntry(rules, cpEntries));
		}
		return cpEntries;		
	}

	/**
	 * Constructs a JRE classpath container on the given VM install
	 * 
	 * @param vm VM install - cannot be <code>null</code>
	 * @param path container path used to resolve this JRE
	 * @param project the project context
	 */
	public JREContainer(IVMInstall vm, IPath path, IJavaProject project) {
		fVMInstall = vm;
		fPath = path;
		fProject = project;
	}
	
	/**
	 * @see IClasspathContainer#getClasspathEntries()
	 */
	public IClasspathEntry[] getClasspathEntries() {
		if (LaunchingPlugin.DEBUG_JRE_CONTAINER) {
			System.out.println("<JRE_CONTAINER> getClasspathEntries() " + this.toString()); //$NON-NLS-1$
			System.out.println("\tJRE:\t" + fVMInstall.getName()); //$NON-NLS-1$
			System.out.println("\tPath:\t" + getPath().toString()); //$NON-NLS-1$
			System.out.println("\tProj:\t" + fProject.getProject().getName()); //$NON-NLS-1$
		}
		IClasspathEntry[] entries = getClasspathEntries(fVMInstall, getPath(), fProject);
		if (LaunchingPlugin.DEBUG_JRE_CONTAINER) {
			System.out.println("\tResolved " + entries.length + " entries:");  //$NON-NLS-1$//$NON-NLS-2$
		}
		return entries;
	}

	/**
	 * @see IClasspathContainer#getDescription()
	 */
	public String getDescription() {
		String environmentId = JavaRuntime.getExecutionEnvironmentId(getPath());
		String tag = null;
		if (environmentId == null) {
			tag = fVMInstall.getName();
		} else {
			tag = environmentId;
		}
		return NLS.bind(LaunchingMessages.JREContainer_JRE_System_Library_1, new String[]{tag});
	}

	/**
	 * @see IClasspathContainer#getKind()
	 */
	public int getKind() {
		return IClasspathContainer.K_DEFAULT_SYSTEM;
	}

	/**
	 * @see IClasspathContainer#getPath()
	 */
	public IPath getPath() {
		return fPath;
	}

}
