/**
 * 
 */
package org.javarosa.workflow.activity;

import org.javarosa.core.Context;
import org.javarosa.core.api.IActivity;
import org.javarosa.core.api.ICommand;
import org.javarosa.core.api.IShell;
import org.javarosa.workflow.PostActionProcessContext;
import org.javarosa.workflow.model.IActionProcessor;

/**
 * @author Clayton Sims
 * @date Jan 13, 2009 
 *
 */
public class PostActionProcessActivity implements IActivity {
	IShell shell;
	IActionProcessor processor;
	Context context; 
	
	public PostActionProcessActivity(IActionProcessor processor) {
		this.processor = processor;
	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#contextChanged(org.javarosa.core.Context)
	 */
	public void contextChanged(Context globalContext) {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#destroy()
	 */
	public void destroy() {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#getActivityContext()
	 */
	public Context getActivityContext() {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#halt()
	 */
	public void halt() {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#resume(org.javarosa.core.Context)
	 */
	public void resume(Context globalContext) {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#setShell(org.javarosa.core.api.IShell)
	 */
	public void setShell(IShell shell) {
		this.shell = shell;
	}

	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#start(org.javarosa.core.Context)
	 */
	public void start(Context context) {
		if(context instanceof PostActionProcessContext) {
			this.context = (PostActionProcessContext)context;
		}
	}
	/* (non-Javadoc)
	 * @see org.javarosa.core.api.IActivity#annotateCommand(org.javarosa.core.api.ICommand)
	 */
	public void annotateCommand(ICommand command) {
		throw new RuntimeException("The Activity Class " + this.getClass().getName() + " Does Not Yet Implement the annotateCommand Interface Method. Please Implement It.");
	}
}