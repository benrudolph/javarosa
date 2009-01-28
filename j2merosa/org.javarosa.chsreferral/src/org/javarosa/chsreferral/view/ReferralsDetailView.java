/**
 * 
 */
package org.javarosa.chsreferral.view;

import javax.microedition.lcdui.Form;

import org.javarosa.chsreferral.model.PatientReferral;
import org.javarosa.core.api.IView;

/**
 * @author Clayton Sims
 * @date Jan 23, 2009 
 *
 */
public class ReferralsDetailView extends Form implements IView {
	PatientReferral ref;

	public ReferralsDetailView(String title) {
		super(title);
	}
	
	public void setReferral(PatientReferral ref) {
		this.ref = ref;
	}
	
	public PatientReferral getReferral() {
		return ref;
	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IView#getScreenObject()
	 */
	public Object getScreenObject() {
		return this;
	}

}