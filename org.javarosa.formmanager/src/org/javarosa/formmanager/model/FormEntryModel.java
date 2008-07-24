package org.javarosa.formmanager.model;

import java.util.Enumeration;
import java.util.Vector;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.formmanager.utility.FormEntryModelListener;

public class FormEntryModel {
    private FormDef form;

    private int activeQuestionIndex;
    private int instanceID;
    private boolean unsavedChanges;

    private Vector observers;
    
    public FormEntryModel(FormDef form) {
    	this(form, -1);
    }
    
    public FormEntryModel(FormDef form, int instanceID) {
    	this.form = form;
    	this.instanceID = instanceID;
    	this.observers = new Vector();
    	
    	this.activeQuestionIndex = (getNumQuestions() > 0 ? 0 : -1);
    	this.unsavedChanges = false;
    }
    
    public int getQuestionIndex () {
    	return activeQuestionIndex;
    }
    
    public void setQuestionIndex (int index) {
    	if (index < -1 || index >= getNumQuestions()) {
    		throw new IllegalArgumentException("Question index out of range");
    	}
    	
    	if (activeQuestionIndex != index) {
    		activeQuestionIndex = index;
    		
    		for (Enumeration e = observers.elements(); e.hasMoreElements(); ) {
    			((FormEntryModelListener)e.nextElement()).questionIndexChanged(activeQuestionIndex);   			
    		}
    	}
    }
    
    public FormDef getForm () {
    	return form;
    }
    
    public int getInstanceID () {
    	return instanceID;
    }
    
    public boolean isSaved () {
    	return !unsavedChanges;
    }
    
    public void modelChanged () {
    	if (!unsavedChanges) {
    		unsavedChanges = true;
    		
    		for (Enumeration e = observers.elements(); e.hasMoreElements(); ) {
    			((FormEntryModelListener)e.nextElement()).saveStateChanged(instanceID, unsavedChanges);   			
    		}		
    	}
    }
    
    public void modelSaved (int instanceID) {
    	this.instanceID = instanceID;
    	unsavedChanges = false;
    	
		for (Enumeration e = observers.elements(); e.hasMoreElements(); ) {
			((FormEntryModelListener)e.nextElement()).saveStateChanged(instanceID, unsavedChanges);   			
		}	  	
    }
        
    //doesn't support groups yet
    public QuestionDef getQuestion (int questionIndex) {
    	return (QuestionDef)form.getChild(questionIndex);
    }
    
    public int getNumQuestions () {
    	return form.getChildren().size();
    }
    
    public boolean isRelevant (int questionIndex) {
    	return getQuestion(questionIndex).isVisible();
    }

    public void registerObservable (FormEntryModelListener feml) {
		if (!observers.contains(feml)) {
			observers.addElement(feml);
		}
    }

    public void unregisterObservable (FormEntryModelListener feml) {
		observers.removeElement(feml);
    }
    
	public void unregisterAll () {
		observers.removeAllElements();
	}
	
//		for (Enumeration e = observers.elements(); e.hasMoreElements(); )
//			((FormEntryModelListener)e.nextElement())....
}