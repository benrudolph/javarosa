package org.javarosa.core.model.utils;

import org.javarosa.core.Context;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.instance.TreeElement;

/**
 * The Context Preload Handler retrieves values from a context
 * object for preloading questions.
 *  
 * @author Alfred Mukudu
 *
 */
public class ContextPreloadHandler implements IPreloadHandler
{
	public Context context;

	public ContextPreloadHandler()
	{

	}
	public ContextPreloadHandler(Context context)
	{
		this.context = context;
		//initHandler();
	}

	/*
	 * (non-Javadoc)
	 * @see org.javarosa.core.model.utils.IPreloadHandler#handlePostProcess(org.javarosa.core.model.IFormDataModel, org.javarosa.core.model.IDataReference, java.lang.String)
	 */
	public boolean handlePostProcess(TreeElement node, String params) {
		// TODO Auto-generated method stub
		return false;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.javarosa.core.model.utils.IPreloadHandler#handlePreload(java.lang.String)
	 */
	public IAnswerData handlePreload(String preloadParams) {
		return preloadContext(preloadParams);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.javarosa.core.model.utils.IPreloadHandler#preloadHandled()
	 */
	public String preloadHandled() {
		return "context";
	}
	//The context preload parameter
	private IAnswerData preloadContext(String preloadParams) {
		String value = "";
		if ("UserID".equals(preloadParams)) {
			String userVal = this.context.getCurrentUser();
			System.out.println("LOGIN NAME IS "+userVal);
			
			if (userVal != null && userVal.length() > 0)
				value = userVal;
		}
		System.out.println(value);
		return new StringData(value);
	}

}