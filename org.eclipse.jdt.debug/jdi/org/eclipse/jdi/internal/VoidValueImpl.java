package org.eclipse.jdi.internal;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.io.DataOutputStream;
import java.io.IOException;

import org.eclipse.jdi.internal.jdwp.JdwpID;

import com.sun.jdi.Type;
import com.sun.jdi.VoidValue;

/**
 * this class implements the corresponding interfaces
 * declared by the JDI specification. See the com.sun.jdi package
 * for more information.
 *
 */
public class VoidValueImpl extends ValueImpl implements VoidValue {
	/** JDWP Tag. */
	public static final byte tag = JdwpID.VOID_TAG;

	/**
	 * Creates new instance.
	 */
	public VoidValueImpl(VirtualMachineImpl vmImpl) {
		super("VoidValue", vmImpl);
	}
	
	/**
	 * @returns tag.
	 */
	public byte getTag() {
		return tag;
	}
	
	/**
	 * @returns type of value.
   	 */
	public Type type() {
		return new VoidTypeImpl(virtualMachineImpl());
	}

	/**
	 * @return Returns true if two values are equal.
	 * @see java.lang.Object#equals(Object)
	 */
	public boolean equals(Object object) {
		return object != null && object.getClass().equals(this.getClass());
	}
	
	/**
	 * @return Returns a has code for this object.
	 * @see java.lang.Object#hashCode
	 */
	public int hashCode() {
		return 0;
	}
	
	/**
	 * Writes value without value tag.
	 */
	public void write(MirrorImpl target, DataOutputStream out) throws IOException {
		// Nothing to write.
	}

	/**
	 * @return Returns description of Mirror object.
	 */
	public String toString() {
		return "(void)";
	}
}
