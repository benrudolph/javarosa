/**
 * 
 */
package org.javarosa.core.model.actions;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.javarosa.core.model.Action;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.Recalculate;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapTagged;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathFuncExpr;

/**
 * @author ctsims
 *
 */
public class SetValueAction extends Action {
	private TreeReference target;
	private XPathExpression value;
	private String explicitValue; 
	
	public SetValueAction() {
		
	}
	
	public SetValueAction(TreeReference target, XPathExpression value) {
		super("setvalue");
		this.target = target;
		this.value = value;
	}
	
	public SetValueAction(TreeReference target, String explicitValue) {
		super("setvalue");
		this.target = target;
		this.explicitValue = explicitValue;
	}
	
	public void processAction(FormDef model) {
		EvaluationContext context = new EvaluationContext(model.getEvaluationContext(), target);
		
		Object result;
		
		if(explicitValue != null) {
			result = explicitValue;
		} else {
			result = XPathFuncExpr.unpack(value.eval(model.getMainInstance(), context));
		}
		int dataType = context.resolveReference(target).getDataType();
		
		model.setValue(Recalculate.wrapData(result, dataType), target);
	}
	
	public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
		target = (TreeReference)ExtUtil.read(in, TreeReference.class, pf);
		explicitValue = ExtUtil.nullIfEmpty(ExtUtil.readString(in));
		if(explicitValue == null) {
			value = (XPathExpression)ExtUtil.read(in, new ExtWrapTagged(), pf);
		}
		
	}

	public void writeExternal(DataOutputStream out) throws IOException {
		ExtUtil.write(out, target);
		
		ExtUtil.write(out, ExtUtil.emptyIfNull(explicitValue));
		if(explicitValue == null) {
			ExtUtil.write(out, new ExtWrapTagged(value));
		}
	}
}