package org.dimagi.chatscreen;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;

import org.celllife.clforms.api.Prompt;

import org.dimagi.utils.ViewUtils;
import org.dimagi.view.Component;
import org.dimagi.view.IRefreshListener;
import org.dimagi.view.NavBar;

import de.enough.polish.util.VectorIterator;

/**
 * The base container for the Chat Screen interface.
 * 
 * The ChatScreenForm is responsible for containing and laying out frames,
 * as well as the control logic used for navigation of protocols.
 * 
 * It is the primary interface between the Protocol object and the View.
 * 
 * @author ctsims
 *
 */
public class ChatScreenForm extends DForm {

	//TODO: Add (...) objects to the top and bottom of the display to signal
	//that there are frames above or below the current view.
	
	private Vector frameSet = new Vector();
	private Vector prompts = new Vector();
	int activeQuestion = 0;
	int totalQuestions = 1;
	
	/**
	 * Creates a new ChatScreen Form
	 */
	public ChatScreenForm() {
		setupComponents();
		definePrompts();
	}

	private void definePrompts() {
		Prompt first = new Prompt();
		first.setLongText("Enter the patient's ID number:");
		first.setShortText("ID");
		first.setFormControlType(Constants.TEXTBOX);
		prompts.addElement((Object) first);		
		addPrompt(first);
		Prompt second = new Prompt();
		second.setLongText("Enter the patient's ID number:");
		second.setShortText("ID");
		second.setFormControlType(Constants.TEXTBOX);
		prompts.addElement((Object) second);		
		
//		Question third = new Question(
//				"Has the patient had any of the following symptoms since their last visit?", "Symptoms",
//				Constants.MULTIPLE_CHOICE, new String[] { "Fever",
//						"Night Sweats", "Weight Loss", "Vomiting" },
//				Constants.LABEL_LEFT);
//		questions.addElement((Object)third);
//		Question fourth = new Question("Name of the city?", "City", Constants.DROPDOWN, 
//				new String[] {"Cambridge", "Boston", "Newton", "Quincy", "Brookline"}, Constants.LABEL_TOP);
//		questions.addElement((Object) fourth);	
	}
	
	/**
	 * Lays out the static components for the form
	 */
	private void setupComponents() {
		int width = this.getWidth();
		int height = this.getHeight();
		int frameCanvasHeight = height - (height / 11);
		getContentComponent().setBackgroundColor(ViewUtils.GREY);
	}

	/**
	 * Pushes a new question onto the stack, setting all other 
	 * questions to inactive status, and displaying the new question
	 * to the user.
	 * 
	 * @param theQuestion The new question to be displayed
	 */
	public void addPrompt(Prompt p) {
		Frame newFrame = new Frame(p);
		newFrame.setWidth(this.getWidth());
		frameSet.addElement(newFrame);
		getContentComponent().add(newFrame);
		setupFrames();
		this.repaint();
	}
	
	public void goToNextPrompt() {
		activeQuestion++;
		// add a new question
		if (activeQuestion == totalQuestions) {
			if ( activeQuestion < prompts.size() ) {
				totalQuestions++;
				addPrompt((Prompt)prompts.elementAt(activeQuestion));
			} else { // repeat questions in loop
			    totalQuestions++;
				addPrompt((Prompt)prompts.elementAt(activeQuestion % 4));
			}
		} else { // advance to question that's already there
			getContentComponent().add((Frame)frameSet.elementAt(activeQuestion));
			setupFrames();
		}
	}
	
	public void goToPreviousPrompt() {
		// Don't do anything if user hits prev command for first question
		if (activeQuestion > 0) {
			getContentComponent().remove((Frame)frameSet.elementAt(activeQuestion));
			activeQuestion--;
			setupFrames();
		}
	}

	/**
	 * Queries all frames for their optimal size, and then lays them out
	 * in a simple stack.
	 */
	private void setupFrames() {
		int frameCanvasHeight = getContentComponent().getHeight()
				- (getContentComponent().getHeight() / 11);

		int frameStart = frameCanvasHeight;
		for (int i=activeQuestion; i >=0; i--) {
			Frame aFrame = (Frame) frameSet.elementAt(i);
			if ( i == activeQuestion ) {
				aFrame.setActiveFrame(true);
			} else {
				aFrame.setActiveFrame(false);
			}
			frameStart -= aFrame.getHeight();
			aFrame.setY(frameStart);
		}
	}
}
		