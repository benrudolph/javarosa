package org.javarosa.formmanager.controller;

import org.javarosa.core.JavaRosaServiceProvider;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.instance.DataModelTree;
import org.javarosa.core.model.storage.DataModelTreeRMSUtility;
import org.javarosa.formmanager.model.FormEntryModel;
import org.javarosa.formmanager.view.FormElementBinding;

import java.util.Date;

public class FormEntryController {
	public static final int QUESTION_OK = 0;
	public static final int QUESTION_REQUIRED_BUT_EMPTY = 1;
	public static final int QUESTION_CONSTRAINT_VIOLATED = 2;
	
	FormEntryModel model;

	public FormEntryController (FormEntryModel model) {
		this.model = model;
	}

	public int questionAnswered (FormElementBinding binding, IAnswerData data) {
		if (binding.instanceNode.required && data == null) {
			return QUESTION_REQUIRED_BUT_EMPTY;
		} else if (!model.getForm().evaluateConstraint(binding.instanceRef, data)) {
			return QUESTION_CONSTRAINT_VIOLATED;
		} else {
			commitAnswer(binding, data);
			stepQuestion(true);
			return QUESTION_OK;
		}
	}

	//TODO: constraint isn't checked here, meaning if you 'save' on a question with invalid data entered in, that data will save
	//without complaint... seems wrong (but oh-so right?)
	public boolean commitAnswer (FormElementBinding binding, IAnswerData data) {
		if (data != null || binding.getValue() != null) {
			//we should check if the data to be saved is already the same as the data in the model, but we can't (no IAnswerData.equals())
			model.getForm().setValue(data, binding.instanceRef, binding.instanceNode);
			model.modelChanged();
			return true;
		} else {
			return false;
		}
	}

	public void stepQuestion (boolean forward) {
		FormIndex index = model.getQuestionIndex();
		do {
			if (forward) {
				index = model.getForm().incrementIndex(index);
			} else {
				index = model.getForm().decrementIndex(index);
			}
		} while (index.isInForm() && !model.isRelevant(index));

		if (index.isBeginningOfFormIndex()) {
			//already at the earliest relevant question
			return;
		} else if (index.isEndOfFormIndex()) {
			model.setFormComplete();
			return;
		}
		selectQuestion(index);
	}

	public void selectQuestion (FormIndex questionIndex) {
		model.setQuestionIndex(questionIndex);
	}

	public void newRepeat (FormIndex questionIndex) {
		model.getForm().createNewRepeat(questionIndex);
	}
	
	//saves model as is; view is responsible for committing any pending data in the current question
	public void save () {
		boolean postProcessModified = model.getForm().postProcessModel();

		if (!model.isSaved() || postProcessModified) {
			FormDef form = model.getForm();
			DataModelTreeRMSUtility utility = (DataModelTreeRMSUtility)JavaRosaServiceProvider.instance().getStorageManager().getRMSStorageProvider().getUtility(DataModelTreeRMSUtility.getUtilityName());
			DataModelTree instance = (DataModelTree)form.getDataModel(); //worry about supporting other data model types later
			int instanceID = model.getInstanceID();

			instance.setName(form.getTitle());
	        instance.setFormId(form.getRecordId());
	        instance.setDateSaved(new Date());

	        //notify of save details
	        //String info = "Form saved to 'Saved Forms' at "+ instance.getDateSaved().toString();
			//Alert a = new Alert("Save Successful!", info, null, AlertType.INFO);
			//a.setTimeout(2000);
			//setDisplay(a);

			if(instanceID == -1) {
				instanceID = utility.writeToRMS(instance);
			} else {
				utility.updateToRMS(instanceID, instance);
			}

			model.modelSaved(instanceID);
		}
	}


	public void setLanguage (String language) {
		model.getForm().getLocalizer().setLocale(language);
	}

	public void cycleLanguage () {
		setLanguage(model.getForm().getLocalizer().getNextLocale());
	}
}