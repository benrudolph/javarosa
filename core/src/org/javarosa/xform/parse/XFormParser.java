/*
 * Copyright (C) 2009 JavaRosa
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.javarosa.xform.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;
import java.util.Vector;

import org.javarosa.core.model.Action;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.DataBinding;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.GroupDef;
import org.javarosa.core.model.IDataReference;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.SubmissionProfile;
import org.javarosa.core.model.actions.SetValueAction;
import org.javarosa.core.model.condition.Condition;
import org.javarosa.core.model.condition.Constraint;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.Recalculate;
import org.javarosa.core.model.condition.Triggerable;
import org.javarosa.core.model.data.AnswerDataFactory;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.InvalidReferenceException;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.util.restorable.Restorable;
import org.javarosa.core.model.util.restorable.RestoreUtils;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localizer;
import org.javarosa.core.services.locale.TableLocaleSource;
import org.javarosa.core.util.DataUtil;
import org.javarosa.core.util.Interner;
import org.javarosa.core.util.OrderedHashtable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.core.util.externalizable.PrototypeFactoryDeprecated;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xform.util.InterningKXmlParser;
import org.javarosa.xform.util.XFormSerializer;
import org.javarosa.xform.util.XFormUtils;
import org.javarosa.xpath.XPathConditional;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.XPathUnsupportedException;
import org.javarosa.xpath.expr.XPathPathExpr;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.kxml2.io.KXmlParser;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
import org.kxml2.kdom.Node;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* droos: i think we need to start storing the contents of the <bind>s in the formdef again */

/**
 * Provides conversion from xform to epihandy object model and vice vasa.
 *
 * @author Daniel Kayiwa
 * @author Drew Roos
 *
 */
public class XFormParser {
    
    //Constants to clean up code and prevent user error
    private static final String ID_ATTR = "id";
    private static final String FORM_ATTR = "form";
    private static final String APPEARANCE_ATTR = "appearance";
    private static final String NODESET_ATTR = "nodeset";
    private static final String LABEL_ELEMENT = "label";
    private static final String VALUE = "value";
    private static final String ITEXT_CLOSE = "')";
    private static final String ITEXT_OPEN = "jr:itext('";
    private static final String BIND_ATTR = "bind";
    private static final String REF_ATTR = "ref";
    private static final String SELECTONE = "select1";
    private static final String SELECT = "select";
    
    public static final String NAMESPACE_JAVAROSA = "http://openrosa.org/javarosa";
    public static final String NAMESPACE_HTML = "http://www.w3.org/1999/xhtml";
    public static final String NAMESPACE_XFORMS = "http://www.w3.org/2002/xforms";

    private static final int CONTAINER_GROUP = 1;
    private static final int CONTAINER_REPEAT = 2;
    
    private static Hashtable<String, IElementHandler> topLevelHandlers;
    private static Hashtable<String, IElementHandler> groupLevelHandlers;
    private static Hashtable<String, Integer> typeMappings;
    private static PrototypeFactoryDeprecated modelPrototypes;
    private static Vector<SubmissionParser> submissionParsers;

    private Reader _reader;
    private Document _xmldoc;
    private FormDef _f;
    
    private Reader _instReader;
    private Document _instDoc;
    
    private boolean modelFound;
    private Hashtable<String, DataBinding> bindingsByID;
    private Vector<DataBinding> bindings;
    private Vector<TreeReference> actionTargets;
    private Vector<TreeReference> repeats;
    private Vector<ItemsetBinding> itemsets;
    private Vector<TreeReference> selectOnes;
    private Vector<TreeReference> selectMultis;
    private Element mainInstanceNode; //top-level data node of the instance; saved off so it can be processed after the <bind>s
    private Vector<Element> instanceNodes; 
    private Vector<String> instanceNodeIdStrs;
    private String defaultNamespace;
    private Vector<String> itextKnownForms;
    
    private static Vector<String> namedActions;
    private static Hashtable<String, IElementHandler> structuredActions;
    
    
    private FormInstance repeatTree; //pseudo-data model tree that describes the repeat structure of the instance;
                                     //useful during instance processing and validation

    //incremented to provide unique question ID for each question
    private int serialQuestionID = 1;
    
    static {
        try {
            staticInit();
        } catch (Exception e) {
            Logger.die("xfparser-static-init", e);
        }
    }

    private static void staticInit() {
        initProcessingRules();
        initTypeMappings();
        modelPrototypes = new PrototypeFactoryDeprecated();
        submissionParsers = new Vector<SubmissionParser>();                    
    }

    private static void initProcessingRules () {
        IElementHandler title = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseTitle(e); } };
        IElementHandler meta = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseMeta(e); } };
        IElementHandler model = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseModel(e); } };
        IElementHandler input = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, Constants.CONTROL_INPUT); } };
        IElementHandler secret = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, Constants.CONTROL_SECRET); } };
        IElementHandler select = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, Constants.CONTROL_SELECT_MULTI); } };
        IElementHandler select1 = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, Constants.CONTROL_SELECT_ONE); } };
        IElementHandler group = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseGroup((IFormElement)parent, e, CONTAINER_GROUP); } };
        IElementHandler repeat = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseGroup((IFormElement)parent, e, CONTAINER_REPEAT); } };
        IElementHandler groupLabel = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseGroupLabel((GroupDef)parent, e); } };
        IElementHandler trigger = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, Constants.CONTROL_TRIGGER); } };
        IElementHandler upload = new IElementHandler () {
            public void handle (XFormParser p, Element e, Object parent) { p.parseUpload((IFormElement)parent, e, Constants.CONTROL_UPLOAD); } };

        groupLevelHandlers = new Hashtable<String, IElementHandler>();
        groupLevelHandlers.put("input", input);
        groupLevelHandlers.put("secret",secret);
        groupLevelHandlers.put(SELECT, select);
        groupLevelHandlers.put(SELECTONE, select1);
        groupLevelHandlers.put("group", group);
        groupLevelHandlers.put("repeat", repeat);
        groupLevelHandlers.put("trigger", trigger); //multi-purpose now; need to dig deeper
        groupLevelHandlers.put(Constants.XFTAG_UPLOAD, upload);

        topLevelHandlers = new Hashtable<String, IElementHandler>();
        for (Enumeration en = groupLevelHandlers.keys(); en.hasMoreElements(); ) {
            String key = (String)en.nextElement();
            topLevelHandlers.put(key, groupLevelHandlers.get(key));
        }
        topLevelHandlers.put("model", model);
        topLevelHandlers.put("title", title);
        topLevelHandlers.put("meta", meta);

        groupLevelHandlers.put(LABEL_ELEMENT, groupLabel);

        structuredActions = new Hashtable<String, IElementHandler>();
        registerStructuredAction("setvalue", new IElementHandler() {
            public void handle (XFormParser p, Element e, Object parent) { p.parseSetValueAction((FormDef)parent, e);}
        });
    }

    private static void initTypeMappings () {
        typeMappings = new Hashtable<String, Integer>();
        typeMappings.put("string", DataUtil.integer(Constants.DATATYPE_TEXT));               //xsd:
        typeMappings.put("integer", DataUtil.integer(Constants.DATATYPE_INTEGER));           //xsd:
        typeMappings.put("long", DataUtil.integer(Constants.DATATYPE_LONG));                 //xsd:
        typeMappings.put("int", DataUtil.integer(Constants.DATATYPE_INTEGER));               //xsd:
        typeMappings.put("decimal", DataUtil.integer(Constants.DATATYPE_DECIMAL));           //xsd:
        typeMappings.put("double", DataUtil.integer(Constants.DATATYPE_DECIMAL));            //xsd:
        typeMappings.put("float", DataUtil.integer(Constants.DATATYPE_DECIMAL));             //xsd:
        typeMappings.put("dateTime", DataUtil.integer(Constants.DATATYPE_DATE_TIME));        //xsd:
        typeMappings.put("date", DataUtil.integer(Constants.DATATYPE_DATE));                 //xsd:
        typeMappings.put("time", DataUtil.integer(Constants.DATATYPE_TIME));                 //xsd:
        typeMappings.put("gYear", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));         //xsd:
        typeMappings.put("gMonth", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));        //xsd:
        typeMappings.put("gDay", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));          //xsd:
        typeMappings.put("gYearMonth", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));    //xsd:
        typeMappings.put("gMonthDay", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));     //xsd:
        typeMappings.put("boolean", DataUtil.integer(Constants.DATATYPE_BOOLEAN));           //xsd:
        typeMappings.put("base64Binary", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));  //xsd:
        typeMappings.put("hexBinary", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));     //xsd:
        typeMappings.put("anyURI", DataUtil.integer(Constants.DATATYPE_UNSUPPORTED));        //xsd:
        typeMappings.put("listItem", DataUtil.integer(Constants.DATATYPE_CHOICE));           //xforms:
        typeMappings.put("listItems", DataUtil.integer(Constants.DATATYPE_CHOICE_LIST));        //xforms:    
        typeMappings.put(SELECTONE, DataUtil.integer(Constants.DATATYPE_CHOICE));            //non-standard    
        typeMappings.put(SELECT, DataUtil.integer(Constants.DATATYPE_CHOICE_LIST));        //non-standard
        typeMappings.put("geopoint", DataUtil.integer(Constants.DATATYPE_GEOPOINT));         //non-standard
        typeMappings.put("barcode", DataUtil.integer(Constants.DATATYPE_BARCODE));           //non-standard
        typeMappings.put("binary", DataUtil.integer(Constants.DATATYPE_BINARY));             //non-standard
    }
    
    private void initState () {
        modelFound = false;
        bindingsByID = new Hashtable<String, DataBinding>();
        bindings = new Vector<DataBinding>();
        actionTargets = new Vector<TreeReference>();
        repeats = new Vector<TreeReference>();
        itemsets = new Vector<ItemsetBinding>();
        selectOnes = new Vector<TreeReference>();
        selectMultis = new Vector<TreeReference>();
        mainInstanceNode = null;
        instanceNodes = new Vector<Element>();
        instanceNodeIdStrs = new Vector<String>();
        repeatTree = null;
        defaultNamespace = null;
        
        itextKnownForms = new Vector<String>();
        itextKnownForms.addElement("long");
        itextKnownForms.addElement("short");
        itextKnownForms.addElement("image");
        itextKnownForms.addElement("audio");
        
        namedActions = new Vector<String>();
        namedActions.addElement("rebuild");
        namedActions.addElement("recalculate");
        namedActions.addElement("revalidate");
        namedActions.addElement("refresh");
        namedActions.addElement("setfocus");
        namedActions.addElement("reset");
    }
    
    XFormParserReporter reporter = new XFormParserReporter();
    
    Interner<String> stringCache;

    public XFormParser(Reader reader) {
        _reader = reader;
    }
    
    public XFormParser(Document doc) {
        _xmldoc = doc;
    }
    
    public XFormParser(Reader form, Reader instance) {
        _reader = form;
        _instReader = instance;
    }
    
    public XFormParser(Document form, Document instance) {
        _xmldoc = form;
        _instDoc = instance;
    }
    
    public void attachReporter(XFormParserReporter reporter) {
        this.reporter = reporter;
    }
    
    public FormDef parse() throws IOException {
        if (_f == null) {
            System.out.println("Parsing form...");
            
            if (_xmldoc == null) {
                _xmldoc = getXMLDocument(_reader, stringCache);
            }
            
            parseDoc();
            
            //load in a custom xml instance, if applicable
            if (_instReader != null) {
                loadXmlInstance(_f, _instReader);
            } else if (_instDoc != null) {
                loadXmlInstance(_f, _instDoc);
            }
        }
        return _f;
    }
    
    public static Document getXMLDocument(Reader reader) throws IOException  {
        return getXMLDocument(reader, null);
    }
    
    public static Document getXMLDocument(Reader reader, Interner<String> stringCache) throws IOException  {
        Document doc = new Document();

        try{
            KXmlParser parser;
            
            if(stringCache != null) {
                parser = new InterningKXmlParser(stringCache);
            } else {
                parser = new KXmlParser();
            }
            
            parser.setInput(reader);
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            doc.parse(parser);
        }  catch (XmlPullParserException e) {
            String errorMsg = "XML Syntax Error at Line: " + e.getLineNumber() +", Column: "+ e.getColumnNumber()+ "!";
            System.err.println(errorMsg);
            e.printStackTrace();
            throw new XFormParseException(errorMsg);
        } catch(IOException e){
            //CTS - 12/09/2012 - Stop swallowing IO Exceptions
            throw e;
        } catch(Exception e){
            //#if debug.output==verbose || debug.output==exception
            String errorMsg = "Unhandled Exception while Parsing XForm";
            System.err.println(errorMsg);
            e.printStackTrace();
            throw new XFormParseException(errorMsg);
            //#endif
        }

        try {
            reader.close();
        } catch (IOException e) {
            System.out.println("Error closing reader");
            e.printStackTrace();
        }
        
        //For escaped unicode strings we end up with a looooot of cruft,
        //so we really want to go through and convert the kxml parsed
        //text (which have lots of characters each as their own string)
        //into one single string
        Stack<Element> q = new Stack<Element>();
        
        q.push(doc.getRootElement());
        while(!q.isEmpty()) {
            Element e = q.pop();
            boolean[] toRemove = new boolean[e.getChildCount()*2];
            String accumulate = "";
            for(int i = 0 ; i < e.getChildCount(); ++i ){
                int type = e.getType(i);
                if(type == Element.TEXT) {
                    String text = e.getText(i);
                    accumulate += text;
                    toRemove[i] = true;
                } else {
                    if(type ==Element.ELEMENT) {
                        q.addElement(e.getElement(i));
                    }
                    String accumulatedString = accumulate.trim();
                    if(accumulatedString.length() != 0) {
                        if(stringCache == null) {
                            e.addChild(i, Element.TEXT, accumulate);
                        } else {
                            e.addChild(i, Element.TEXT, stringCache.intern(accumulate));
                        }
                        accumulate = "";
                        ++i;
                    } else {
                        accumulate = "";
                    }
                }
            }
            if(accumulate.trim().length() != 0) {
                if(stringCache == null) {
                    e.addChild(Element.TEXT, accumulate);
                } else {
                    e.addChild(Element.TEXT, stringCache.intern(accumulate));
                }
            }
            for(int i = e.getChildCount() - 1; i >= 0 ; i-- ){
                if(toRemove[i]) {
                    e.removeChild(i);
                }
            }
        }
        
        return doc;
    }

    private void parseDoc() {
        _f = new FormDef();
        
        initState();
        defaultNamespace = _xmldoc.getRootElement().getNamespaceUri(null);
        parseElement(_xmldoc.getRootElement(), _f, topLevelHandlers);
        collapseRepeatGroups(_f);
        
        //parse the non-main instance nodes first
        //we assume that the non-main instances won't
        //reference the main node, so we do them first.
        //if this assumption is wrong, well, then we're screwed.
        if(instanceNodes.size() > 1)
        {
            for(int i = 1; i < instanceNodes.size(); i++)
            {
                Element e = instanceNodes.elementAt(i);
                String srcLocation = e.getAttributeValue(null, "src");
				String instanceid = instanceNodeIdStrs.elementAt(i);
                
                DataInstance di;
				if(srcLocation != null) {
					//If there's a src, we shouldn't accept a body, so make 
					//sure these are real children and not whitespace issues
					if(e.getChildCount() > 0) {
						for(int k = 0; k < e.getChildCount(); ++k) {
							switch(e.getType(k)) {
							case Element.TEXT:
								if("".equals(e.getText(i).trim())) {
									//this isn't real data
									continue;
								}
							case Element.IGNORABLE_WHITESPACE:
								continue;
							case Element.ELEMENT:
								throw new XFormParseException("Instance declaration for instance " + instanceid + " contains both a src and a body, only one is permitted", e);
							}
						}
					}
					di = new ExternalDataInstance(srcLocation, instanceid);
                } else {
                    FormInstance fi = parseInstance(e, false);
                    loadInstanceData(e, fi.getRoot(), _f);
                    di = fi;
                }
                _f.addNonMainInstance(di);

            }
        }
        //now parse the main instance
        if(mainInstanceNode != null) {
            FormInstance fi = parseInstance(mainInstanceNode, true);
            addMainInstanceToFormDef(mainInstanceNode, fi);
            
            //set the main instance
            _f.setInstance(fi);
        }
        
    }

    private void parseElement (Element e, Object parent, Hashtable<String, IElementHandler> handlers) { //,
//            boolean allowUnknownElements, boolean allowText, boolean recurseUnknown) {
        String name = e.getName();

        String[] suppressWarningArr = {
            "html",
            "head",
            "body",
            "xform",
            "chooseCaption",
            "addCaption",
            "addEmptyCaption",
            "delCaption",
            "doneCaption",
            "doneEmptyCaption",
            "mainHeader",
            "entryHeader",
            "delHeader"
        };
        Vector<String> suppressWarning = new Vector<String>();
        for (int i = 0; i < suppressWarningArr.length; i++) {
            suppressWarning.addElement(suppressWarningArr[i]);
        }
        
        IElementHandler eh = handlers.get(name);
        if (eh != null) {
            eh.handle(this, e, parent);
        } else {
            if (!suppressWarning.contains(name)) {
                reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP,
                        "Unrecognized element [" + name    + "]. Ignoring and processing children...",
                        getVagueLocation(e));
            }
            for (int i = 0; i < e.getChildCount(); i++) {
                if (e.getType(i) == Element.ELEMENT) {
                    parseElement(e.getElement(i), parent, handlers);
                }
            }
        }
    }

    private void parseTitle (Element e) {
        Vector usedAtts = new Vector(); //no attributes parsed in title.
        String title = getXMLText(e, true);
        System.out.println("Title: \"" + title + "\"");
        _f.setTitle(title);
        if(_f.getName() == null) {
            //Jan 9, 2009 - ctsims
            //We don't really want to allow for forms without
            //some unique ID, so if a title is available, use
            //that.
            _f.setName(title);
        }
        
        
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }
    
    private void parseMeta (Element e) {
        Vector<String> usedAtts = new Vector<String>();
        int attributes = e.getAttributeCount();
        for(int i = 0 ; i < attributes ; ++i) {
            String name = e.getAttributeName(i);
            String value = e.getAttributeValue(i);
            if("name".equals(name)) {
                _f.setName(value);
            }
        }
        

        usedAtts.addElement("name");
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }

    //for ease of parsing, we assume a model comes before the controls, which isn't necessarily mandated by the xforms spec
    private void parseModel (Element e) {
        Vector<String> usedAtts = new Vector<String>(); //no attributes parsed in title.
        Vector<Element> delayedParseElements = new Vector<Element>();
        
        if (modelFound) {
            reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE,
                            "Multiple models not supported. Ignoring subsequent models.", getVagueLocation(e));
            return;
        }
        modelFound = true;
        
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
        for (int i = 0; i < e.getChildCount(); i++) {
            
            int type = e.getType(i);
            Element child = (type == Node.ELEMENT ? e.getElement(i) : null);
            String childName = (child != null ? child.getName() : null);
            
            if ("itext".equals(childName)) {
                parseIText(child);
            } else if ("instance".equals(childName)) {
                //we save parsing the instance node until the end, giving us the information we need about
                //binds and data types and such
                saveInstanceNode(child);
            } else if (BIND_ATTR.equals(childName)) { //<instance> must come before <bind>s
                parseBind(child);
            } else if("submission".equals(childName)) {
                delayedParseElements.addElement(child);
            } else if(namedActions.contains(childName) || (childName != null && structuredActions.containsKey(childName))) {
                delayedParseElements.addElement(child);
            } else { //invalid model content
                if (type == Node.ELEMENT) {
                    if (child.getNamespace().equals(NAMESPACE_XFORMS)) {
                        throw new XFormParseException("Unrecognized top-level tag [" + childName + "] found within <model>",child);
                    }
                } else if (type == Node.TEXT && getXMLText(e, i, true).length() != 0) {
                    throw new XFormParseException("Unrecognized text content found within <model>: \"" + getXMLText(e, i, true) + "\"",child == null ? e : child);                    
                }
            }
            
            if(child == null || BIND_ATTR.equals(childName) || "itext".equals(childName)) {
                //Clayton Sims - Jun 17, 2009 - This code is used when the stinginess flag
                //is set for the build. It dynamically wipes out old model nodes once they're
                //used. This is sketchy if anything else plans on touching the nodes.
                //This code can be removed once we're pull-parsing
                //#if org.javarosa.xform.stingy
                e.removeChild(i);
                --i;
                //#endif
            }
        }
        
        //Now parse out the submission/action blocks (we needed the binds to all be set before we could)
        for(Element child : delayedParseElements) {
            String name = child.getName();
            if(name.equals("submission")) {
                parseSubmission(child);
            } else {
                //For now, anything that isn't a submission is an action
                if(namedActions.contains(name)) {
                    parseNamedAction(child);
                } else {
                    structuredActions.get(name).handle(this, child, _f);
                }
            }
        }
    }
    
    private void parseNamedAction(Element action) {
        //TODO: Anything useful
    }
    
    private void parseSetValueAction(FormDef form, Element e) {
        String ref = e.getAttributeValue(null, REF_ATTR);
        String bind = e.getAttributeValue(null, BIND_ATTR);
        
        String event = e.getAttributeValue(null, "event");
        
        IDataReference dataRef = null;
        boolean refFromBind = false;
        
        
        //TODO: There is a _lot_ of duplication of this code, fix that!
        if (bind != null) {
            DataBinding binding = bindingsByID.get(bind);
            if (binding == null) {
                throw new XFormParseException("XForm Parse: invalid binding ID in submit'" + bind + "'", e);
            }
            dataRef = binding.getReference();
            refFromBind = true;
        } else if (ref != null) {
            dataRef = new XPathReference(ref);
        } else {
            throw new XFormParseException("setvalue action with no target!", e);
        }
        
        if (dataRef != null) {
            if (!refFromBind) {
                dataRef = getAbsRef(dataRef, TreeReference.rootRef());
            }
        }
        
        String valueRef = e.getAttributeValue(null, "value");
        Action action;
        TreeReference treeref = FormInstance.unpackReference(dataRef);
        
        registerActionTarget(treeref);
        if(valueRef == null) {
            if(e.getChildCount() == 0 || !e.isText(0)) {
                throw new XFormParseException("No 'value' attribute and no inner value set in <setvalue> associated with: " + treeref, e);
            }
            //Set expression
            action = new SetValueAction(treeref, e.getText(0));
        } else {
            try {
                action = new SetValueAction(treeref, XPathParseTool.parseXPath(valueRef));
            } catch (XPathSyntaxException e1) {
                e1.printStackTrace();
                throw new XFormParseException("Invalid XPath in value set action declaration: '" + valueRef + "'", e);
            }
        }
        form.registerEventListener(event, action);
    }
    
    private void parseSubmission(Element submission) {
        String id = submission.getAttributeValue(null, ID_ATTR);
        
        //These two are always required
        String method = submission.getAttributeValue(null, "method");
        String action = submission.getAttributeValue(null, "action");
        
        SubmissionParser parser = new SubmissionParser();
        for(SubmissionParser p : submissionParsers) {
            if(p.matchesCustomMethod(method)) {
                parser = p;
            }
        }
        
        //These two might exist, but if neither do, we just assume you want the entire instance.
        String ref = submission.getAttributeValue(null, REF_ATTR);
        String bind = submission.getAttributeValue(null, BIND_ATTR);
        
        IDataReference dataRef = null;
        boolean refFromBind = false;
        
        if (bind != null) {
            DataBinding binding = bindingsByID.get(bind);
            if (binding == null) {
                throw new XFormParseException("XForm Parse: invalid binding ID in submit'" + bind + "'", submission);
            }
            dataRef = binding.getReference();
            refFromBind = true;
        } else if (ref != null) {
            dataRef = new XPathReference(ref);
        } else {
            //no reference! No big deal, assume we want the root reference
            dataRef = new XPathReference("/");
        }

        if (dataRef != null) {
            if (!refFromBind) {
                dataRef = getAbsRef(dataRef, TreeReference.rootRef());
            }
        }

        SubmissionProfile profile = parser.parseSubmission(method, action, dataRef, submission );
        
        if(id == null) {
            //default submission profile
            _f.setDefaultSubmission(profile);
        } else {
            //typed submission profile
            _f.addSubmissionProfile(id, profile);
        }
    }

    private void saveInstanceNode (Element instance) {
        Element instanceNode = null;
        String instanceId = instance.getAttributeValue("", "id");
        
        for (int i = 0; i < instance.getChildCount(); i++) {
            if (instance.getType(i) == Node.ELEMENT) {
                if (instanceNode != null) {
                    throw new XFormParseException("XForm Parse: <instance> has more than one child element", instance);
                } else {
                    instanceNode = instance.getElement(i);
                }
            }
        }
        
        if(instanceNode == null) {
            //no kids
            instanceNode = instance;
        }
        
        if (mainInstanceNode == null) {
            mainInstanceNode = instanceNode;
		} else if(instanceId == null) {
			throw new XFormParseException("XForm Parse: Non-main <instance> element requires an id attribute", instance);
        }
        
        instanceNodes.addElement(instanceNode);
        instanceNodeIdStrs.addElement(instanceId);
        
            
        
    }
    
    protected QuestionDef parseUpload(IFormElement parent, Element e, int controlUpload) {
        Vector usedAtts = new Vector();
        QuestionDef question = parseControl(parent, e, controlUpload);
        String mediaType = e.getAttributeValue(null, "mediatype");
        if ("image/*".equals(mediaType)) {
            // NOTE: this could be further expanded. 
            question.setControlType(Constants.CONTROL_IMAGE_CHOOSE);
        } else if("audio/*".equals(mediaType)) {
            question.setControlType(Constants.CONTROL_AUDIO_CAPTURE);
        } else if ("video/*".equals(mediaType)) {
            question.setControlType(Constants.CONTROL_VIDEO_CAPTURE);
        }
        
        usedAtts.addElement("mediatype");
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
        return question;
    }
    
    protected QuestionDef parseControl (IFormElement parent, Element e, int controlType) {
        QuestionDef question = new QuestionDef();
        question.setID(serialQuestionID++); //until we come up with a better scheme
        
        Vector usedAtts = new Vector();
        usedAtts.addElement(REF_ATTR);
        usedAtts.addElement(BIND_ATTR);
        usedAtts.addElement(APPEARANCE_ATTR);
        
        IDataReference dataRef = null;
        boolean refFromBind = false;
        
        String ref = e.getAttributeValue(null, REF_ATTR);
        String bind = e.getAttributeValue(null, BIND_ATTR);
        
        if (bind != null) {
            DataBinding binding = bindingsByID.get(bind);
            if (binding == null) {
                throw new XFormParseException("XForm Parse: invalid binding ID '" + bind + "'", e);
            }
            dataRef = binding.getReference();
            refFromBind = true;
        } else if (ref != null) {
            try {
                dataRef = new XPathReference(ref);
            } catch(RuntimeException el) {
                System.out.println(this.getVagueLocation(e));
                throw el;
            }
        } else {
            if (controlType == Constants.CONTROL_TRIGGER) {
                //TODO: special handling for triggers? also, not all triggers created equal
            } else {
                throw new XFormParseException("XForm Parse: input control with neither 'ref' nor 'bind'",e);
            }
        }

        if (dataRef != null) {
            if (!refFromBind) {
                dataRef = getAbsRef(dataRef, parent);
            }
            question.setBind(dataRef);
            
            if (controlType == Constants.CONTROL_SELECT_ONE) {
                selectOnes.addElement((TreeReference)dataRef.getReference());
            } else if (controlType == Constants.CONTROL_SELECT_MULTI) {
                selectMultis.addElement((TreeReference)dataRef.getReference());
            }
        }        

        boolean isSelect = (controlType == Constants.CONTROL_SELECT_MULTI || controlType == Constants.CONTROL_SELECT_ONE);
        question.setControlType(controlType);
        question.setAppearanceAttr(e.getAttributeValue(null, APPEARANCE_ATTR));

        for (int i = 0; i < e.getChildCount(); i++) {
            int type = e.getType(i);
            Element child = (type == Node.ELEMENT ? e.getElement(i) : null);
            String childName = (child != null ? child.getName() : null);

            if (LABEL_ELEMENT.equals(childName)) {
                parseQuestionLabel(question, child);
            } else if ("hint".equals(childName) || "help".equals(childName)) {
                parseHelperText(question, child);
            } else if (isSelect && "item".equals(childName)) {
                parseItem(question, child);
            } else if (isSelect && "itemset".equals(childName)) {
                parseItemset(question, child, parent);
            }
        }
        if (isSelect) {
            if (question.getNumChoices() > 0 && question.getDynamicChoices() != null) {
                throw new XFormParseException("Multiple choice question contains both literal choices and <itemset>");
            } else if (question.getNumChoices() == 0 && question.getDynamicChoices() == null) {
                throw new XFormParseException("Multiple choice question has no choices");
            }
        }
            
        parent.addChild(question);
        

        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
        
        return question;
    }

    private void parseQuestionLabel (QuestionDef q, Element e) {
        String label = getLabel(e);
        String ref = e.getAttributeValue("", REF_ATTR);
        
        Vector usedAtts = new Vector();
        usedAtts.addElement(REF_ATTR);
        
        if (ref != null) {
            if (ref.startsWith(ITEXT_OPEN) && ref.endsWith(ITEXT_CLOSE)) {
                String textRef = ref.substring(ITEXT_OPEN.length(), ref.indexOf(ITEXT_CLOSE));

                verifyTextMappings(textRef, "Question <label>", true);
                q.setTextID(textRef);
            } else {
                throw new RuntimeException("malformed ref [" + ref + "] for <label>");
            }
        } else {
            q.setLabelInnerText(label);
        }
        

        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }

    private void parseGroupLabel (GroupDef g, Element e) {
        if (g.getRepeat())
            return; //ignore child <label>s for <repeat>; the appropriate <label> must be in the wrapping <group>
        
        Vector usedAtts = new Vector();
        usedAtts.addElement(REF_ATTR);
        
        
        String label = getLabel(e);
        String ref = e.getAttributeValue("", REF_ATTR);

        if (ref != null) {
            if (ref.startsWith(ITEXT_OPEN) && ref.endsWith(ITEXT_CLOSE)) {
                String textRef = ref.substring(ITEXT_OPEN.length(), ref.indexOf(ITEXT_CLOSE));

                verifyTextMappings(textRef, "Group <label>", true);
                g.setTextID(textRef);
            } else {
                throw new RuntimeException("malformed ref [" + ref + "] for <label>");
            }
        } else {
            g.setLabelInnerText(label);
        }
        

        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }
    
    private String getLabel (Element e){
        if(e.getChildCount() == 0) return null;
        
        recurseForOutput(e);
        
        StringBuffer sb = new StringBuffer();
        for(int i = 0; i<e.getChildCount();i++){
            if(e.getType(i)!=Node.TEXT && !(e.getChild(i) instanceof String)){
                Object b = e.getChild(i);
                Element child = (Element)b;
                
                //If the child is in the HTML namespace, retain it. 
                if(NAMESPACE_HTML.equals(child.getNamespace())) {
                    sb.append(XFormSerializer.elementToString(child));
                } else {
                    //Otherwise, ignore it.
                    System.out.println("Unrecognized tag inside of text: <"  + child.getName() + ">. " +
                            "Did you intend to use HTML markup? If so, ensure that the element is defined in " +
                            "the HTML namespace.");
                }
            }else{
                sb.append(e.getText(i));
            }
        }
        
        String s = sb.toString().trim();
        
        return s;
    }
    
    private void recurseForOutput(Element e){
        if(e.getChildCount() == 0) return;
        
        for(int i=0;i<e.getChildCount();i++){
            int kidType = e.getType(i);
            if(kidType == Node.TEXT) { continue; }
            if(e.getChild(i) instanceof String) { continue; }
            Element kid = (Element)e.getChild(i);
            
                //is just text
            if(kidType == Node.ELEMENT && XFormUtils.isOutput(kid)){
                String s = "${"+parseOutput(kid)+"}";
                e.removeChild(i);
                e.addChild(i, Node.TEXT, s);
                
                //has kids? Recurse through them and swap output tag for parsed version
            }else if(kid.getChildCount() !=0){
                recurseForOutput(kid);
                //is something else
            }else{
                continue;
            }
        }
    }
        
    private String parseOutput (Element e) {
        Vector<String> usedAtts = new Vector<String>();
        usedAtts.addElement(REF_ATTR);
        usedAtts.addElement(VALUE);
        
        String xpath = e.getAttributeValue(null, REF_ATTR);
        if (xpath == null) {
            xpath = e.getAttributeValue(null, VALUE);
        }
        if (xpath == null) {
            throw new XFormParseException("XForm Parse: <output> without 'ref' or 'value'",e);    
        }
        
        XPathConditional expr = null;
        try {
            expr = new XPathConditional(xpath);
        } catch (XPathSyntaxException xse) {
            reporter.error("Invalid XPath expression in <output> [" + xpath + "]! " + xse.getMessage());
            return "";
        }

        int index = -1;
        if (_f.getOutputFragments().contains(expr)) {
            index = _f.getOutputFragments().indexOf(expr);
        } else {
            index = _f.getOutputFragments().size();
            _f.getOutputFragments().addElement(expr);
        }
        
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
        return String.valueOf(index);
    }
    
    /**
     * Handles hint elements (text-only) and help elements (similar, but may include multimedia)
     * @param q The QuestionDef object to augment with the hint/help
     * @param e The Element to parse
     */
    private void parseHelperText (QuestionDef q, Element e) {
        Vector usedAtts = new Vector();
        usedAtts.addElement(REF_ATTR);
        String XMLText = getXMLText(e, true);
        String innerText = getLabel(e);
        String ref = e.getAttributeValue("", REF_ATTR);
        String name = e.getName();

        if (ref != null) {
            if (ref.startsWith(ITEXT_OPEN) && ref.endsWith(ITEXT_CLOSE)) {
                String textRef = ref.substring(ITEXT_OPEN.length(), ref.indexOf(ITEXT_CLOSE));

                verifyTextMappings(textRef, "<" + name + ">", false);
                if (name.equals("hint")) {
                    q.setHintTextID(textRef);
                } else {
                    q.setHelpTextID(textRef);
                }
            } else {
                throw new RuntimeException("malformed ref [" + ref + "] for <" + name + ">");
            }
        } else if (name.equals("hint")) {
            q.setHintInnerText(innerText);
            q.setHintText(XMLText);
        } else {
            q.setHelpInnerText(innerText);
            q.setHelp(XMLText, null, null, null);
            // TODO jls: set multimedia
        }
        
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }

    private void parseItem (QuestionDef q, Element e) {
        final int MAX_VALUE_LEN = 32;
        
        //catalogue of used attributes in this method/element
        Vector usedAtts = new Vector();
        Vector labelUA = new Vector();
        Vector valueUA = new Vector();
        labelUA.addElement(REF_ATTR);
        valueUA.addElement(FORM_ATTR);
        
        String labelInnerText = null;
        String textRef = null;
        String value = null;

        for (int i = 0; i < e.getChildCount(); i++) {
            int type = e.getType(i);
            Element child = (type == Node.ELEMENT ? e.getElement(i) : null);
            String childName = (child != null ? child.getName() : null);

            if (LABEL_ELEMENT.equals(childName)) {
                
                //print attribute warning for child element
                if(XFormUtils.showUnusedAttributeWarning(child, labelUA)){
                    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(child, labelUA), getVagueLocation(child));
                }
                labelInnerText = getLabel(child);
                String ref = child.getAttributeValue("", REF_ATTR);

                if (ref != null) {
                    if (ref.startsWith(ITEXT_OPEN) && ref.endsWith(ITEXT_CLOSE)) {
                        textRef = ref.substring(ITEXT_OPEN.length(), ref.indexOf(ITEXT_CLOSE));

                        verifyTextMappings(textRef, "Item <label>", true);
                    } else {
                        throw new XFormParseException("malformed ref [" + ref + "] for <item>",child);
                    }
                }
            } else if (VALUE.equals(childName)) {
                value = getXMLText(child, true);
                
                //print attribute warning for child element
                if(XFormUtils.showUnusedAttributeWarning(child, valueUA)){
                    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(child, valueUA), getVagueLocation(child));
                }
                
                if (value != null)  {
                    if (value.length() > MAX_VALUE_LEN) {
                        reporter.warning(XFormParserReporter.TYPE_ERROR_PRONE,
                                "choice value [" + value + "] is too long; max. suggested length " + MAX_VALUE_LEN + " chars",
                                getVagueLocation(child));
                    }
                
                    //validate
                    for (int k = 0; k < value.length(); k++) {
                        char c = value.charAt(k);
                                        
                        if (" \n\t\f\r\'\"`".indexOf(c) >= 0) {
                            boolean isMultiSelect = (q.getControlType() == Constants.CONTROL_SELECT_MULTI);
                            reporter.warning(XFormParserReporter.TYPE_ERROR_PRONE,
                                    (isMultiSelect ? SELECT : SELECTONE) + " question <value>s [" + value + "] " +
                                    (isMultiSelect ? "cannot" : "should not") + " contain spaces, and are recommended not to contain apostraphes/quotation marks",
                                    getVagueLocation(child));
                            break;
                        }
                    }
                }
            }
        }
        
        if (textRef == null && labelInnerText == null) {
            throw new XFormParseException("<item> without proper <label>",e);
        }
        if (value == null) {
            throw new XFormParseException("<item> without proper <value>",e);
        }

        if (textRef != null) {
            q.addSelectChoice(new SelectChoice(textRef, value));
        } else {
            q.addSelectChoice(new SelectChoice(null,labelInnerText, value, false));
        }
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP,XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
    }

    private void parseItemset (QuestionDef q, Element e, IFormElement qparent) {
        ItemsetBinding itemset = new ItemsetBinding();
        
        ////////////////USED FOR PARSER WARNING OUTPUT ONLY
        //catalogue of used attributes in this method/element
        Vector usedAtts = new Vector();
        Vector labelUA = new Vector(); //for child with name 'label'
        Vector valueUA = new Vector(); //for child with name 'value'
        Vector copyUA = new Vector(); //for child with name 'copy'
        usedAtts.addElement(NODESET_ATTR);
        labelUA.addElement(REF_ATTR);
        valueUA.addElement(REF_ATTR);
        valueUA.addElement(FORM_ATTR);
        copyUA.addElement(REF_ATTR);
        ////////////////////////////////////////////////////
        
        String nodesetStr = e.getAttributeValue("", NODESET_ATTR);
        if(nodesetStr == null ) throw new RuntimeException("No nodeset attribute in element: ["+e.getName()+"]. This is required. (Element Printout:"+XFormSerializer.elementToString(e)+")");
        XPathPathExpr path = XPathReference.getPathExpr(nodesetStr);
        itemset.nodesetExpr = new XPathConditional(path);
        itemset.contextRef = getFormElementRef(q);
        itemset.nodesetRef = FormInstance.unpackReference(getAbsRef(new XPathReference(path.getReference(true)), itemset.contextRef));
        
        for (int i = 0; i < e.getChildCount(); i++) {
            int type = e.getType(i);
            Element child = (type == Node.ELEMENT ? e.getElement(i) : null);
            String childName = (child != null ? child.getName() : null);

            if (LABEL_ELEMENT.equals(childName)) {
                String labelXpath = child.getAttributeValue("", REF_ATTR);
                boolean labelItext = false;
                
                //print unused attribute warning message for child element
                if(XFormUtils.showUnusedAttributeWarning(child, labelUA)){
                    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(child, labelUA), getVagueLocation(child));
                }
                /////////////////////////////////////////////////////////////
                
                if (labelXpath != null) {
                    if (labelXpath.startsWith("jr:itext(") && labelXpath.endsWith(")")) {
                        labelXpath = labelXpath.substring("jr:itext(".length(), labelXpath.indexOf(")"));
                        labelItext = true;
                    }
                } else {
                    throw new XFormParseException("<label> in <itemset> requires 'ref'");
                }
                
                XPathPathExpr labelPath = XPathReference.getPathExpr(labelXpath);
                itemset.labelRef = FormInstance.unpackReference(getAbsRef(new XPathReference(labelPath), itemset.nodesetRef));
                itemset.labelExpr = new XPathConditional(labelPath);
                itemset.labelIsItext = labelItext;
            } else if ("copy".equals(childName)) {
                String copyRef = child.getAttributeValue("", REF_ATTR);

                //print unused attribute warning message for child element
                if(XFormUtils.showUnusedAttributeWarning(child, copyUA)){
                    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(child, copyUA), getVagueLocation(child));
                }
                
                if (copyRef == null) {
                    throw new XFormParseException("<copy> in <itemset> requires 'ref'");
                }
                
                itemset.copyRef = FormInstance.unpackReference(getAbsRef(new XPathReference(copyRef), itemset.nodesetRef));
                itemset.copyMode = true;
            } else if (VALUE.equals(childName)) {
                String valueXpath = child.getAttributeValue("", REF_ATTR);
                
                //print unused attribute warning message for child element
                if(XFormUtils.showUnusedAttributeWarning(child, valueUA)){
                    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(child, valueUA), getVagueLocation(child));
                }
                
                if (valueXpath == null) {
                    throw new XFormParseException("<value> in <itemset> requires 'ref'");
                }
                
                XPathPathExpr valuePath = XPathReference.getPathExpr(valueXpath);
                itemset.valueRef = FormInstance.unpackReference(getAbsRef(new XPathReference(valuePath), itemset.nodesetRef));
                itemset.valueExpr = new XPathConditional(valuePath);
                itemset.copyMode = false;
            }
        }
        
        if (itemset.labelRef == null) {
            throw new XFormParseException("<itemset> requires <label>");
        } else if (itemset.copyRef == null && itemset.valueRef == null) {            
            throw new XFormParseException("<itemset> requires <copy> or <value>");
        }
        
        if (itemset.copyRef != null) {
            if (itemset.valueRef == null) {
                reporter.warning(XFormParserReporter.TYPE_TECHNICAL, "<itemset>s with <copy> are STRONGLY recommended to have <value> as well; pre-selecting, default answers, and display of answers will not work properly otherwise",getVagueLocation(e));
            } else if (!itemset.copyRef.isParentOf(itemset.valueRef, false)) {
                throw new XFormParseException("<value> is outside <copy>");                    
            }
        }
        
        q.setDynamicChoices(itemset);
        itemsets.addElement(itemset);
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP,XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
    }
    
    private void parseGroup (IFormElement parent, Element e, int groupType) {
        GroupDef group = new GroupDef();
        group.setID(serialQuestionID++); //until we come up with a better scheme
        IDataReference dataRef = null;
        boolean refFromBind = false;
        
        Vector usedAtts = new Vector();
        usedAtts.addElement(REF_ATTR);
        usedAtts.addElement(NODESET_ATTR);
        usedAtts.addElement(BIND_ATTR);
        usedAtts.addElement(APPEARANCE_ATTR);
        usedAtts.addElement("count");
        usedAtts.addElement("noAddRemove");
        
        if (groupType == CONTAINER_REPEAT) {
            group.setRepeat(true);
        }
        
        String ref = e.getAttributeValue(null, REF_ATTR);
        String nodeset = e.getAttributeValue(null, NODESET_ATTR);
        String bind = e.getAttributeValue(null, BIND_ATTR);
        group.setAppearanceAttr(e.getAttributeValue(null, APPEARANCE_ATTR));
        
        if (bind != null) {
            DataBinding binding = bindingsByID.get(bind);
            if (binding == null) {
                throw new XFormParseException("XForm Parse: invalid binding ID [" + bind + "]",e);
            }
            dataRef = binding.getReference();
            refFromBind = true;
        } else {
            if (group.getRepeat()) {
                if (nodeset != null) {
                    dataRef = new XPathReference(nodeset);
                } else {
                    throw new XFormParseException("XForm Parse: <repeat> with no binding ('bind' or 'nodeset')",e);
                }
            } else {
                if (ref != null) {
                    dataRef = new XPathReference(ref);
                } //<group> not required to have a binding
            }
        }

        if (!refFromBind) {
            dataRef = getAbsRef(dataRef, parent);
        }
        group.setBind(dataRef);
        
        if (group.getRepeat()) {
            repeats.addElement((TreeReference)dataRef.getReference());

            String countRef = e.getAttributeValue(NAMESPACE_JAVAROSA, "count");
            if (countRef != null) {
                group.count = getAbsRef(new XPathReference(countRef), parent);
                group.noAddRemove = true;
            } else {
                group.noAddRemove = (e.getAttributeValue(NAMESPACE_JAVAROSA, "noAddRemove") != null);                
            }
        }
        
        for (int i = 0; i < e.getChildCount(); i++) {
            int type = e.getType(i);
            Element child = (type == Node.ELEMENT ? e.getElement(i) : null);
            String childName = (child != null ? child.getName() : null);
            String childNamespace = (child != null ? child.getNamespace() : null);

            if (group.getRepeat() && NAMESPACE_JAVAROSA.equals(childNamespace)) {                
                if ("chooseCaption".equals(childName)) {
                    group.chooseCaption = getLabel(child);
                } else if ("addCaption".equals(childName)) {
                    group.addCaption = getLabel(child);
                } else if ("delCaption".equals(childName)) {
                    group.delCaption = getLabel(child);
                } else if ("doneCaption".equals(childName)) {
                    group.doneCaption = getLabel(child);
                } else if ("addEmptyCaption".equals(childName)) {
                    group.addEmptyCaption = getLabel(child);
                } else if ("doneEmptyCaption".equals(childName)) {
                    group.doneEmptyCaption = getLabel(child);
                } else if ("entryHeader".equals(childName)) {
                    group.entryHeader = getLabel(child);
                } else if ("delHeader".equals(childName)) {
                    group.delHeader = getLabel(child);
                } else if ("mainHeader".equals(childName)) {
                    group.mainHeader = getLabel(child);
                }
            }
        }

        //the case of a group wrapping a repeat is cleaned up in a post-processing step (collapseRepeatGroups)
        
        for (int i = 0; i < e.getChildCount(); i++) {
            if (e.getType(i) == Element.ELEMENT) {
                parseElement(e.getElement(i), group, groupLevelHandlers);
            }
        }
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }

        parent.addChild(group);
    }

    private TreeReference getFormElementRef (IFormElement fe) {
        if (fe instanceof FormDef) {
            TreeReference ref = TreeReference.rootRef();
            ref.add(mainInstanceNode.getName(), 0);
            return ref;
        } else {
            return (TreeReference)fe.getBind().getReference();    
        }
    }
    
    private IDataReference getAbsRef (IDataReference ref, IFormElement parent) {
        return getAbsRef(ref, getFormElementRef(parent));
    }
    
    //take a (possibly relative) reference, and make it absolute based on its parent
    public static IDataReference getAbsRef (IDataReference ref, TreeReference parentRef) {
        TreeReference tref;
        
        if (!parentRef.isAbsolute()) {
            throw new RuntimeException("XFormParser.getAbsRef: parentRef must be absolute");
        }
        
        if (ref != null) {
            tref = (TreeReference)ref.getReference();
        } else {
            tref = TreeReference.selfRef(); //only happens for <group>s with no binding
        }        
        
        tref = tref.parent(parentRef);
        if (tref == null) {
            throw new XFormParseException("Binding path [" + tref + "] not allowed with parent binding of [" + parentRef + "]");
        }
        
        return new XPathReference(tref);
    }
    
    //collapse groups whose only child is a repeat into a single repeat that uses the label of the wrapping group
    private static void collapseRepeatGroups (IFormElement fe) {
        if (fe.getChildren() == null)
            return;
        
        for (int i = 0; i < fe.getChildren().size(); i++) {
            IFormElement child = fe.getChild(i);
            GroupDef group = null;
            if (child instanceof GroupDef)
                group = (GroupDef)child;

            if (group != null) {
                if (!group.getRepeat() && group.getChildren().size() == 1) {
                    IFormElement grandchild = (IFormElement)group.getChildren().elementAt(0);
                    GroupDef repeat = null;
                    if (grandchild instanceof GroupDef)
                        repeat = (GroupDef)grandchild;
                    
                    if (repeat != null && repeat.getRepeat()) {
                        //collapse the wrapping group
                        
                        //merge group into repeat
                        //id - later
                        //name - later
                        repeat.setLabelInnerText(group.getLabelInnerText());
                        repeat.setTextID(group.getTextID());
//                        repeat.setLongText(group.getLongText());
//                        repeat.setShortText(group.getShortText());
//                        repeat.setLongTextID(group.getLongTextID(), null);
//                        repeat.setShortTextID(group.getShortTextID(), null);                        
                        //don't merge binding; repeat will always already have one
                        
                        //replace group with repeat
                        fe.getChildren().setElementAt(repeat, i);
                        group = repeat;
                    }
                }
                
                collapseRepeatGroups(group);
            }
        }
    }
    
    private void parseIText (Element itext) {
        Localizer l = new Localizer(true, true);
        _f.setLocalizer(l);
        l.registerLocalizable(_f);

        Vector usedAtts = new Vector(); //used for warning message
        
        for (int i = 0; i < itext.getChildCount(); i++) {
            Element trans = itext.getElement(i);
            if (trans == null || !trans.getName().equals("translation"))
                continue;

            parseTranslation(l, trans);
        }

        if (l.getAvailableLocales().length == 0)
            throw new XFormParseException("no <translation>s defined",itext);

        if (l.getDefaultLocale() == null)
            l.setDefaultLocale(l.getAvailableLocales()[0]);
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(itext, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(itext, usedAtts), getVagueLocation(itext));
        }
    }

    private void parseTranslation (Localizer l, Element trans) {
        /////for warning message
        Vector usedAtts = new Vector();
        usedAtts.addElement("lang");
        usedAtts.addElement("default");
        /////////////////////////
        
        String lang = trans.getAttributeValue("", "lang");
        if (lang == null || lang.length() == 0) {
            throw new XFormParseException("no language specified for <translation>",trans);
        }
        String isDefault = trans.getAttributeValue("", "default");

        if (!l.addAvailableLocale(lang)) {
            throw new XFormParseException("duplicate <translation> for language '" + lang + "'",trans);
        }

        if (isDefault != null) {
            if (l.getDefaultLocale() != null)
                throw new XFormParseException("more than one <translation> set as default",trans);
            l.setDefaultLocale(lang);
        }
        
        TableLocaleSource source = new TableLocaleSource();

        //source.startEditing();
        for (int j = 0; j < trans.getChildCount(); j++) {
            Element text = trans.getElement(j);
            if (text == null || !text.getName().equals("text")) {
                continue;
            }

            parseTextHandle(source, text);
            //Clayton Sims - Jun 17, 2009 - This code is used when the stinginess flag
            //is set for the build. It dynamically wipes out old model nodes once they're
            //used. This is sketchy if anything else plans on touching the nodes.
            //This code can be removed once we're pull-parsing
            //#if org.javarosa.xform.stingy
            trans.removeChild(j);
            --j;
            //#endif
        }
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(trans, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(trans, usedAtts), getVagueLocation(trans));
        }
        
        //source.stopEditing();
        l.registerLocaleResource(lang, source);
    }

    private void parseTextHandle (TableLocaleSource l, Element text) {
        String id = text.getAttributeValue("", ID_ATTR);
        
        //used for parser warnings...
        Vector usedAtts = new Vector();
        Vector childUsedAtts = new Vector();
        usedAtts.addElement(ID_ATTR);
        usedAtts.addElement(FORM_ATTR);
        childUsedAtts.addElement(FORM_ATTR);
        childUsedAtts.addElement(ID_ATTR);
        //////////
        
        if (id == null || id.length() == 0) {
            throw new XFormParseException("no id defined for <text>",text);
        }

        for (int k = 0; k < text.getChildCount(); k++) {
            Element value = text.getElement(k);
            if (value == null) continue;
            if(!value.getName().equals(VALUE)){
                throw new XFormParseException("Unrecognized element ["+value.getName()+"] in Itext->translation->text");
            }

            String form = value.getAttributeValue("", FORM_ATTR);
            if (form != null && form.length() == 0) {
                form = null;
            }
            String data = getLabel(value);
            if (data == null) {
                data = "";
            }

            String textID = (form == null ? id : id + ";" + form);  //kind of a hack
            if (l.hasMapping(textID)) {
                throw new XFormParseException("duplicate definition for text ID \"" + id + "\" and form \"" + form + "\""+". Can only have one definition for each text form.",text);
            }
            l.setLocaleMapping(textID, data);
            
            //print unused attribute warning message for child element
            if(XFormUtils.showUnusedAttributeWarning(value, childUsedAtts)){
                reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(value, childUsedAtts), getVagueLocation(value));
            }
        }
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(text, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(text, usedAtts), getVagueLocation(text));
        }
    }

    private boolean hasITextMapping (String textID, String locale) {
        Localizer l = _f.getLocalizer();
        return l.hasMapping(locale == null ? l.getDefaultLocale() : locale, textID);
    }
    
    private void verifyTextMappings (String textID, String type, boolean allowSubforms) {
        Localizer l = _f.getLocalizer();
        String[] locales = l.getAvailableLocales();
        
        for (int i = 0; i < locales.length; i++) {
            //Test whether there is a default translation, or whether there is any special form available.
            if (!(hasITextMapping(textID, locales[i]) ||
                    (allowSubforms && hasSpecialFormMapping(textID, locales[i])))) {
                if (locales[i].equals(l.getDefaultLocale())) {
                    throw new XFormParseException(type + " '" + textID + "': text is not localizable for default locale [" + l.getDefaultLocale() + "]!");
                } else {
                    reporter.warning(XFormParserReporter.TYPE_TECHNICAL, type + " '" + textID + "': text is not localizable for locale " + locales[i] + ".", null);
                }
            }
        }
    }
    
    /**
     * Tests whether or not there is any form (default or special) for the provided
     * text id.
     * 
     * @return True if a translation is present for the given textID in the form. False otherwise
     */
    private boolean hasSpecialFormMapping(String textID, String locale) {
        //First check our guesses
        for(String guess : itextKnownForms) {
            if(hasITextMapping(textID + ";" + guess, locale)) {
                return true;
            }
        }
        //Otherwise this sucks and we have to test the keys
        OrderedHashtable table = _f.getLocalizer().getLocaleData(locale);
        for(Enumeration keys = table.keys() ; keys.hasMoreElements() ;) {
            String key = (String)keys.nextElement();
            if(key.startsWith(textID + ";")) {
                //A key is found, pull it out, add it to the list of guesses, and return positive
                String textForm = key.substring(key.indexOf(";") + 1, key.length());
                //Kind of a long story how we can end up getting here. It involves the default locale loading values
                //for the other locale, but isn't super good.
                //TODO: Clean up being able to get here
                if(!itextKnownForms.contains(textForm)) {
                    System.out.println("adding unexpected special itext form: " + textForm + " to list of expected forms");
                    itextKnownForms.addElement(textForm);
                }
                return true;
            }
        }
        return false;
    }
    
    protected DataBinding processStandardBindAttributes( Vector usedAtts, Element e) {
        usedAtts.addElement(ID_ATTR);
        usedAtts.addElement(NODESET_ATTR);
        usedAtts.addElement("type");
        usedAtts.addElement("relevant");
        usedAtts.addElement("required");
        usedAtts.addElement("readonly");
        usedAtts.addElement("constraint");
        usedAtts.addElement("constraintMsg");
        usedAtts.addElement("calculate");
        usedAtts.addElement("preload");
        usedAtts.addElement("preloadParams");

        DataBinding binding  = new DataBinding();
        
        
        binding.setId(e.getAttributeValue("", ID_ATTR));

        String nodeset = e.getAttributeValue(null, NODESET_ATTR);
        if (nodeset == null) {
            throw new XFormParseException("XForm Parse: <bind> without nodeset",e);
        }
        IDataReference ref;
        try {            
            ref = new XPathReference(nodeset);
        } catch(XPathException xpe) {
            throw new XFormParseException(xpe.getMessage());
        }
        ref = getAbsRef(ref, _f);
        binding.setReference(ref);

        binding.setDataType(getDataType(e.getAttributeValue(null, "type")));

        String xpathRel = e.getAttributeValue(null, "relevant");
        if (xpathRel != null) {
            if ("true()".equals(xpathRel)) {
                binding.relevantAbsolute = true;
            } else if ("false()".equals(xpathRel)) {
                binding.relevantAbsolute = false;
            } else {
                try{
                    Condition c = buildCondition(xpathRel, "relevant", ref);
                    c = (Condition)_f.addTriggerable(c);
                    binding.relevancyCondition = c;
                } catch(XPathUnsupportedException xue){
                    throw buildParseException(nodeset, xue.getMessage(),xpathRel, "display condition");
                }
            }
        }

        String xpathReq = e.getAttributeValue(null, "required");
        if (xpathReq != null) {
            if ("true()".equals(xpathReq)) {
                binding.requiredAbsolute = true;
            } else if ("false()".equals(xpathReq)) {
                binding.requiredAbsolute = false;
            } else {
                try{
                    Condition c = buildCondition(xpathReq, "required", ref);
                    c = (Condition)_f.addTriggerable(c);
                    binding.requiredCondition = c;
                } catch(XPathUnsupportedException xue){
                    throw buildParseException(nodeset, xue.getMessage(),xpathReq, "required condition");
                }
            }
        }

        String xpathRO = e.getAttributeValue(null, "readonly");
        if (xpathRO != null) {
            if ("true()".equals(xpathRO)) {
                binding.readonlyAbsolute = true;
            } else if ("false()".equals(xpathRO)) {
                binding.readonlyAbsolute = false;
            } else {
                try{
                    Condition c = buildCondition(xpathRO, "readonly", ref);
                    c = (Condition)_f.addTriggerable(c);
                    binding.readonlyCondition = c;
                } catch(XPathUnsupportedException xue){
                    throw buildParseException(nodeset, xue.getMessage(),xpathRO, "read-only condition");
                }
            }
        }

        String xpathConstr = e.getAttributeValue(null, "constraint");
        if (xpathConstr != null) {
            try {
                binding.constraint = new XPathConditional(xpathConstr);
            } catch (XPathSyntaxException xse) {
                throw buildParseException(nodeset, xse.getMessage(),xpathConstr, "constraint");
            }
            binding.constraintMessage = e.getAttributeValue(NAMESPACE_JAVAROSA, "constraintMsg");
        }
        
        String xpathCalc = e.getAttributeValue(null, "calculate");
        if (xpathCalc != null) {
            Recalculate r;
            try {
                r = buildCalculate(xpathCalc, ref);
            } catch (XPathSyntaxException xpse) {
                throw buildParseException(nodeset, xpse.getMessage(),xpathCalc, "calculate");
            }
            r = (Recalculate)_f.addTriggerable(r);
            binding.calculate = r;
        }

        binding.setPreload(e.getAttributeValue(NAMESPACE_JAVAROSA, "preload"));
        binding.setPreloadParams(e.getAttributeValue(NAMESPACE_JAVAROSA, "preloadParams"));
        
        return binding;
    }
    
    public static XFormParseException buildParseException(String nodeset, String message, String expression, String attribute){
        return new XFormParseException("Problem with bind for " + nodeset + " contains invalid " + attribute + " expression [" + expression + "] " + message);
    }

    protected void parseBind (Element e) {
        Vector usedAtts = new Vector();

        DataBinding binding = processStandardBindAttributes( usedAtts, e);
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }

        addBinding(binding);
    }

    private Condition buildCondition (String xpath, String type, IDataReference contextRef) {
        XPathConditional cond;
        int trueAction = -1, falseAction = -1;
        
        String prettyType;

        if ("relevant".equals(type)) {
            prettyType = "display condition";
            trueAction = Condition.ACTION_SHOW;
            falseAction = Condition.ACTION_HIDE;
        } else if ("required".equals(type)) {
            prettyType = "require condition";
            trueAction = Condition.ACTION_REQUIRE;
            falseAction = Condition.ACTION_DONT_REQUIRE;
        } else if ("readonly".equals(type)) {
            prettyType = "readonly condition";
            trueAction = Condition.ACTION_DISABLE;
            falseAction = Condition.ACTION_ENABLE;
        } else{
            prettyType = "unknown condition";
        }

        try {
            cond = new XPathConditional(xpath);
        } catch (XPathSyntaxException xse) {
            
            String errorMessage = "Encountered a problem with " + prettyType + " for node ["  + contextRef.getReference().toString() + "] at line: " + xpath + ", " +  xse.getMessage();
            
            reporter.error(errorMessage);
            
            throw new XFormParseException(errorMessage);
        }
                
        Condition c = new Condition(cond, trueAction, falseAction, FormInstance.unpackReference(contextRef));
        return c;
    }
    
    private static Recalculate buildCalculate (String xpath, IDataReference contextRef) throws XPathSyntaxException {
        XPathConditional calc = new XPathConditional(xpath);
                
        Recalculate r = new Recalculate(calc, FormInstance.unpackReference(contextRef));
        return r;
    }
    
    protected void addBinding (DataBinding binding) {
        bindings.addElement(binding);
        
        if (binding.getId() != null) {
            if (bindingsByID.put(binding.getId(), binding) != null) {
                throw new XFormParseException("XForm Parse: <bind>s with duplicate ID: '" + binding.getId() + "'");
            }
        }
    }
        
    //e is the top-level _data_ node of the instance (immediate (and only) child of <instance>)
    private void addMainInstanceToFormDef(Element e, FormInstance instanceModel) {
        //TreeElement root = buildInstanceStructure(e, null);
        loadInstanceData(e, instanceModel.getRoot(), _f);
        
        checkDependencyCycles();
        _f.setInstance(instanceModel);
        try {
            _f.finalizeTriggerables();
        } catch(IllegalStateException ise) {
            throw new XFormParseException(ise.getMessage() == null ? "Form has an illegal cycle in its calculate and relevancy expressions!" : ise.getMessage());
        }
        
        //print unused attribute warning message for parent element
        //if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
        //    reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        //}
    }
    
    private FormInstance parseInstance (Element e, boolean isMainInstance) {
        String name = instanceNodeIdStrs.elementAt(instanceNodes.indexOf(e));
        
        TreeElement root = buildInstanceStructure(e, null, !isMainInstance ? name : null, e.getNamespace());
        FormInstance instanceModel = new FormInstance(root, !isMainInstance ? name : null);
        if(isMainInstance)
        {
            instanceModel.setName(_f.getTitle());
        }
        else
        {
            instanceModel.setName(name);
        }
        
        Vector usedAtts = new Vector();
        usedAtts.addElement("version");
        usedAtts.addElement("uiVersion");
        usedAtts.addElement("name");
        
        String schema = e.getNamespace();
        if (schema != null && schema.length() > 0 && !schema.equals(defaultNamespace)) {
            instanceModel.schema = schema;
        }
        instanceModel.formVersion = e.getAttributeValue(null, "version");
        instanceModel.uiVersion = e.getAttributeValue(null, "uiVersion");
        
        loadNamespaces(e, instanceModel);
        if(isMainInstance)
        {
            processRepeats(instanceModel);
            verifyBindings(instanceModel);
            verifyActions(instanceModel);
        }
        applyInstanceProperties(instanceModel);
        
        //print unused attribute warning message for parent element
        if(XFormUtils.showUnusedAttributeWarning(e, usedAtts)){
            reporter.warning(XFormParserReporter.TYPE_UNKNOWN_MARKUP, XFormUtils.unusedAttWarning(e, usedAtts), getVagueLocation(e));
        }
        
        return instanceModel;
    }
    
    
    
    private static Hashtable<String, String> loadNamespaces(Element e, FormInstance tree) {
        Hashtable<String, String> prefixes = new Hashtable<String, String>();
        for(int i = 0 ; i < e.getNamespaceCount(); ++i ) {
            String uri = e.getNamespaceUri(i);
            String prefix = e.getNamespacePrefix(i);
            if(uri != null && prefix != null) {
                tree.addNamespace(prefix, uri);
            }
        }
        return prefixes;
    }
    
    public static TreeElement buildInstanceStructure (Element node, TreeElement parent) {
        return buildInstanceStructure(node, parent, null, node.getNamespace());
    }
    
    //parse instance hierarchy and turn into a skeleton model; ignoring data content, but respecting repeated nodes and 'template' flags
    public static TreeElement buildInstanceStructure (Element node, TreeElement parent, String instanceName, String docnamespace) {
        TreeElement element = null;

        //catch when text content is mixed with children
        int numChildren = node.getChildCount();        
        boolean hasText = false;
        boolean hasElements = false;
        for (int i = 0; i < numChildren; i++) {
            switch (node.getType(i)) {
            case Node.ELEMENT:
                hasElements = true; break;
            case Node.TEXT:
                if (node.getText(i).trim().length() > 0)
                    hasText = true;
                break;
            }
        }
        if (hasElements && hasText) {
            System.out.println("Warning: instance node '" + node.getName() + "' contains both elements and text as children; text ignored");
        }
        
        //check for repeat templating
        String name = node.getName();
        int multiplicity;
        if (node.getAttributeValue(NAMESPACE_JAVAROSA, "template") != null) {
            multiplicity = TreeReference.INDEX_TEMPLATE;
            if (parent != null && parent.getChild(name, TreeReference.INDEX_TEMPLATE) != null) {
                throw new XFormParseException("More than one node declared as the template for the same repeated set [" + name + "]",node);
            }
        } else {
            multiplicity = (parent == null ? 0 : parent.getChildMultiplicity(name));
        }
            
        
        String modelType = node.getAttributeValue(NAMESPACE_JAVAROSA, "modeltype");
        //create node; handle children
        if(modelType == null) {
            element = new TreeElement(name, multiplicity);
            element.setInstanceName(instanceName);
        } else {
            if( typeMappings.get(modelType) == null ){
                throw new XFormParseException("ModelType " + modelType + " is not recognized.",node);                
            }
            element = (TreeElement)modelPrototypes.getNewInstance(((Integer)typeMappings.get(modelType)).toString());
            if(element == null) {
                element = new TreeElement(name, multiplicity);
                System.out.println("No model type prototype available for " + modelType);
            } else {
                element.setName(name);
                element.setMult(multiplicity);
            }
        }
        if(node.getNamespace() != null) {
            if(!node.getNamespace().equals(docnamespace)) {
                element.setNamespace(node.getNamespace());
            }
        }


        if (hasElements) {
            for (int i = 0; i < numChildren; i++) {
                if (node.getType(i) == Node.ELEMENT) {
                    element.addChild(buildInstanceStructure(node.getElement(i), element, instanceName, docnamespace));
                }
            }
        }

        //handle attributes
        if (node.getAttributeCount() > 0) {
            for (int i = 0; i < node.getAttributeCount(); i++) {
                String attrNamespace = node.getAttributeNamespace(i);
                String attrName = node.getAttributeName(i);
                if (attrNamespace.equals(NAMESPACE_JAVAROSA) && attrName.equals("template")) {
                    continue;
                }
                if (attrNamespace.equals(NAMESPACE_JAVAROSA) && attrName.equals("recordset")) {
                    continue;
                }
                
                element.setAttribute(attrNamespace, attrName, node.getAttributeValue(i));
            }
        }
        
        return element;
    }
    
    private Vector<TreeReference> getRepeatableRefs () {
        Vector<TreeReference> refs = new Vector<TreeReference>();

        for (int i = 0; i < repeats.size(); i++) {
            refs.addElement((TreeReference)repeats.elementAt(i));
        }

        for (int i = 0; i < itemsets.size(); i++) {
            ItemsetBinding itemset = (ItemsetBinding)itemsets.elementAt(i);
            TreeReference srcRef = itemset.nodesetRef;
            if (!refs.contains(srcRef)) {
                //CTS: Being an itemset root is not sufficient to mark
                //a node as repeatable. It has to be nonstatic (which it
                //must be inherently unless there's a wildcard).
                boolean nonstatic = true;
                for(int j = 0 ; j < srcRef.size(); ++j) {
                    if(TreeReference.NAME_WILDCARD.equals(srcRef.getName(j))) {
                        nonstatic = false;
                    }
                }
                
                //CTS: we're also going to go ahead and assume that all external 
                //instance are static (we can't modify them TODO: This may only be 
                //the case if the instances are of specific types (non Tree-Element 
                //style). Revisit if needed.
                if(srcRef.getInstanceName() != null) {
                    nonstatic = false;
                }
                if(nonstatic) {
                    refs.addElement(srcRef);
                }
            }
            
            if (itemset.copyMode) {
                TreeReference destRef = itemset.getDestRef();
                if (!refs.contains(destRef)) {
                    refs.addElement(destRef);
                }                
            }
        }
        
        return refs;
    }
    
    //pre-process and clean up instance regarding repeats; in particular:
    // 1) flag all repeat-related nodes as repeatable
    // 2) catalog which repeat template nodes are explicitly defined, and note which repeats bindings lack templates
    // 3) remove template nodes that are not valid for a repeat binding
    // 4) generate template nodes for repeat bindings that do not have one defined explicitly
    // (removed) 5) give a stern warning for any repeated instance nodes that do not correspond to a repeat binding
    // 6) verify that all sets of repeated nodes are homogeneous
    private void processRepeats (FormInstance instance) {
        flagRepeatables(instance);
        processTemplates(instance);
        //2013-05-17 - ctsims - No longer call this, since we don't do the check
        //checkDuplicateNodesAreRepeatable(instance.getRoot());    
        checkHomogeneity(instance);
    }

    //flag all nodes identified by repeat bindings as repeatable
    private void flagRepeatables (FormInstance instance) {
        Vector<TreeReference> refs = getRepeatableRefs();
        for (int i = 0; i < refs.size(); i++) {
            TreeReference ref = refs.elementAt(i);
            Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref, true);
            for (int j = 0; j < nodes.size(); j++) {
                TreeReference nref = nodes.elementAt(j);
                TreeElement node = instance.resolveReference(nref);
                if (node != null) { // catch '/'
                    node.setRepeatable(true);
                }
            }
        }        
    }
    
    private void processTemplates (FormInstance instance) {
        repeatTree = buildRepeatTree(getRepeatableRefs(), instance.getRoot().getName());
        
        Vector<TreeReference> missingTemplates = new Vector<TreeReference>();
        checkRepeatsForTemplate(instance, repeatTree, missingTemplates);

        removeInvalidTemplates(instance, repeatTree);
        createMissingTemplates(instance, missingTemplates);
    }
    
    //build a pseudo-data model tree that describes the repeat structure of the instance
    //result is a FormInstance collapsed where all indexes are 0, and repeatable nodes are flagged as such
    //return null if no repeats
    //ignores (invalid) repeats that bind outside the top-level instance data node
    private static FormInstance buildRepeatTree (Vector<TreeReference> repeatRefs, String topLevelName) {
        TreeElement root = new TreeElement(null, 0);
        
        for (int i = 0; i < repeatRefs.size(); i++) {
            TreeReference repeatRef = repeatRefs.elementAt(i);
            //check and see if this references a repeat from a non-main instance, if so, skip it
            if(repeatRef.getInstanceName() != null)
            {
                continue;
            }
            if (repeatRef.size() <= 1) {
                //invalid repeat: binds too high. ignore for now and error will be raised in verifyBindings
                continue;
            }
            
            TreeElement cur = root;
            for (int j = 0; j < repeatRef.size(); j++) {
                String name = repeatRef.getName(j);
                TreeElement child = cur.getChild(name, 0);
                if (child == null) {
                    child = new TreeElement(name, 0);
                    cur.addChild(child);
                }
                
                cur = child;
            }
            cur.setRepeatable(true);
        }
        
        if (root.getNumChildren() == 0)
            return null;
        else
            return new FormInstance(root.getChild(topLevelName, TreeReference.DEFAULT_MUTLIPLICITY));
    }

    //checks which repeat bindings have explicit template nodes; returns a vector of the bindings that do not
    private static void checkRepeatsForTemplate (FormInstance instance, FormInstance repeatTree, Vector<TreeReference> missingTemplates) {
        if (repeatTree != null)
            checkRepeatsForTemplate(repeatTree.getRoot(), TreeReference.rootRef(), instance, missingTemplates);
    }
    
    //helper function for checkRepeatsForTemplate
    private static void checkRepeatsForTemplate (TreeElement repeatTreeNode, TreeReference ref, FormInstance instance, Vector<TreeReference> missing) {
        String name = repeatTreeNode.getName();
        int mult = (repeatTreeNode.isRepeatable() ? TreeReference.INDEX_TEMPLATE : 0);
        ref = ref.extendRef(name, mult);
        
        if (repeatTreeNode.isRepeatable()) {
            TreeElement template = instance.resolveReference(ref);
            if (template == null) {
                missing.addElement(ref);
            }
        }
            
        for (int i = 0; i < repeatTreeNode.getNumChildren(); i++) {
            checkRepeatsForTemplate(repeatTreeNode.getChildAt(i), ref, instance, missing);
        }
    }
    
    //iterates through instance and removes template nodes that are not valid. a template is invalid if:
    //  it is declared for a node that is not repeatable
    //  it is for a repeat that is a child of another repeat and is not located within the parent's template node
    private void removeInvalidTemplates (FormInstance instance, FormInstance repeatTree) {
        removeInvalidTemplates(instance.getRoot(), (repeatTree == null ? null : repeatTree.getRoot()), true);
    }
    
    //helper function for removeInvalidTemplates
    private boolean removeInvalidTemplates (TreeElement instanceNode, TreeElement repeatTreeNode, boolean templateAllowed) {
        int mult = instanceNode.getMult();
        boolean repeatable = (repeatTreeNode == null ? false : repeatTreeNode.isRepeatable());
        
        if (mult == TreeReference.INDEX_TEMPLATE) {
            if (!templateAllowed) {
                reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, "Template nodes for sub-repeats must be located within the template node of the parent repeat; ignoring template... [" + instanceNode.getName() + "]", null);
                return true;
            } else if (!repeatable) {
                reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, "Warning: template node found for ref that is not repeatable; ignoring... [" + instanceNode.getName() + "]", null);
                return true;
            }
        }
        
        if (repeatable && mult != TreeReference.INDEX_TEMPLATE)
            templateAllowed = false;
        
        for (int i = 0; i < instanceNode.getNumChildren(); i++) {
            TreeElement child = instanceNode.getChildAt(i);
            TreeElement rchild = (repeatTreeNode == null ? null : repeatTreeNode.getChild(child.getName(), 0));
            
            if (removeInvalidTemplates(child, rchild, templateAllowed)) {
                instanceNode.removeChildAt(i);
                i--;
            }
        }
        return false;
    }
    
    //if repeatables have no template node, duplicate first as template
    private void createMissingTemplates (FormInstance instance, Vector<TreeReference> missingTemplates) {
        //it is VERY important that the missing template refs are listed in depth-first or breadth-first order... namely, that
        //every ref is listed after a ref that could be its parent. checkRepeatsForTemplate currently behaves this way
        for (int i = 0; i < missingTemplates.size(); i++) {
            TreeReference templRef = missingTemplates.elementAt(i);
            TreeReference firstMatch;
            
            //make template ref generic and choose first matching node
            TreeReference ref = templRef.clone();
            for (int j = 0; j < ref.size(); j++) {
                ref.setMultiplicity(j, TreeReference.INDEX_UNBOUND);
            }
            Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref);
            if (nodes.size() == 0) {
                //binding error; not a single node matches the repeat binding; will be reported later
                continue;
            } else {
                firstMatch = nodes.elementAt(0);
            }
            
            try {
                instance.copyNode(firstMatch, templRef);
            } catch (InvalidReferenceException e) {
                reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, "Could not create a default repeat template; this is almost certainly a homogeneity error! Your form will not work! (Failed on " + templRef.toString() + ")", null);
            }
            trimRepeatChildren(instance.resolveReference(templRef)); 
        }
    }
    
    //trim repeatable children of newly created template nodes; we trim because the templates are supposed to be devoid of 'data',
    //  and # of repeats for a given repeat node is a kind of data. trust me
    private static void trimRepeatChildren (TreeElement node) {
        for (int i = 0; i < node.getNumChildren(); i++) {
            TreeElement child = node.getChildAt(i);
            if (child.isRepeatable()) {
                node.removeChildAt(i);
                i--;
            } else {
                trimRepeatChildren(child);
            }
        }
    }
    
    private static void checkDuplicateNodesAreRepeatable (TreeElement node) {
        int mult = node.getMult();
        if (mult > 0) { //repeated node
            if (!node.isRepeatable()) {
                //2013-05-17 - ctsims - I removed our check here, since we now support this
                //much more cleanly.
                
                //In theory, we should possibly make sure there are no _binds_ defined on these
                //nodes at the very least, but that can be its own step
            }
        }
        
        for (int i = 0; i < node.getNumChildren(); i++) {
            checkDuplicateNodesAreRepeatable(node.getChildAt(i));
        }
    }
    
    //check repeat sets for homogeneity
    private void checkHomogeneity (FormInstance instance) {
        Vector<TreeReference> refs = getRepeatableRefs();
        for (int i = 0; i < refs.size(); i++) {
            TreeReference ref = refs.elementAt(i);
            TreeElement template = null;
            Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref);
            for (int j = 0; j < nodes.size(); j++) {
                TreeReference nref = nodes.elementAt(j);
                TreeElement node = instance.resolveReference(nref); 
                if (node == null) //don't crash on '/'... invalid repeat binding will be caught later
                    continue;
                
                if (template == null)
                    template = instance.getTemplate(nref);
                
                if (!FormInstance.isHomogeneous(template, node)) {
                    reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, "Not all repeated nodes for a given repeat binding [" + nref.toString() + "] are homogeneous! This will cause serious problems!", null);
                }
            }
        }
    }
    
    private void verifyBindings (FormInstance instance) {
        //check <bind>s (can't bind to '/', bound nodes actually exist)
        for (int i = 0; i < bindings.size(); i++) {
            DataBinding bind = bindings.elementAt(i);
            TreeReference ref = FormInstance.unpackReference(bind.getReference());
            
            if (ref.size() == 0) {
                System.out.println("Cannot bind to '/'; ignoring bind...");
                bindings.removeElementAt(i);
                i--;
            } else {
                Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref, true);
                if (nodes.size() == 0) {
                    reporter.warning(XFormParserReporter.TYPE_ERROR_PRONE, "<bind> defined for a node that doesn't exist [" + ref.toString() + "]. The node's name was probably changed and the bind should be updated. ", null);
                }
            }
        }
                
        //check <repeat>s (can't bind to '/' or '/data')
        Vector<TreeReference> refs = getRepeatableRefs();
        for (int i = 0; i < refs.size(); i++) {
            TreeReference ref = refs.elementAt(i);
            
            if (ref.size() <= 1) {
                throw new XFormParseException("Cannot bind repeat to '/' or '/" + mainInstanceNode.getName() + "'");
            }
        }

        //check control/group/repeat bindings (bound nodes exist, question can't bind to '/')
        Vector<String> bindErrors = new Vector<String>();
        verifyControlBindings(_f, instance, bindErrors);
        if (bindErrors.size() > 0) {
            String errorMsg = "";
            for (int i = 0; i < bindErrors.size(); i++) {
                errorMsg += bindErrors.elementAt(i) + "\n";
            }
            throw new XFormParseException(errorMsg);
        }
        
        //check that repeat members bind to the proper scope (not above the binding of the parent repeat, and not within any sub-repeat (or outside repeat))
        verifyRepeatMemberBindings(_f, instance, null);
        
        //check that label/copy/value refs are children of nodeset ref, and exist
        verifyItemsetBindings(instance);
        
        verifyItemsetSrcDstCompatibility(instance);
    }
    
    private void verifyActions (FormInstance instance) {
        //check the target of actions which are manipulating real values
        for (int i = 0; i < actionTargets.size(); i++) {
            TreeReference target = actionTargets.elementAt(i);
            Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(target, true);
            if (nodes.size() == 0) {
                throw new XFormParseException("Invalid Action - Targets non-existent node: " + target.toString(true));
            }
        }
    }
    
    private void verifyControlBindings (IFormElement fe, FormInstance instance, Vector<String> errors) { //throws XmlPullParserException {
        if (fe.getChildren() == null)
            return;
        
        for (int i = 0; i < fe.getChildren().size(); i++) {
            IFormElement child = fe.getChildren().elementAt(i);
            IDataReference ref = null;
            String type = null;
            
            if (child instanceof GroupDef) {
                ref = ((GroupDef)child).getBind();
                type = (((GroupDef)child).getRepeat() ? "Repeat" : "Group");
            } else if (child instanceof QuestionDef) {
                ref = ((QuestionDef)child).getBind();
                type = "Question";
            }
            TreeReference tref = FormInstance.unpackReference(ref);

            if (child instanceof QuestionDef && tref.size() == 0) {
                //group can bind to '/'; repeat can't, but that's checked above
                reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, "Cannot bind control to '/'",null);
            } else {
                Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(tref, true);
                if (nodes.size() == 0) {
                    String error = type+ " bound to non-existent node: [" + tref.toString() + "]";
                    reporter.error(error);
                    errors.addElement(error);
                }
                //we can't check whether questions map to the right kind of node ('data' node vs. 'sub-tree' node) as that depends
                //on the question's data type, which we don't know yet
            }
            
            verifyControlBindings(child, instance, errors);
        }
    }
    
    private void verifyRepeatMemberBindings (IFormElement fe, FormInstance instance, GroupDef parentRepeat) {
        if (fe.getChildren() == null)
            return;
        
        for (int i = 0; i < fe.getChildren().size(); i++) {
            IFormElement child = fe.getChildren().elementAt(i);
            boolean isRepeat = (child instanceof GroupDef && ((GroupDef)child).getRepeat());
            
            //get bindings of current node and nearest enclosing repeat
            TreeReference repeatBind = (parentRepeat == null ? TreeReference.rootRef() : FormInstance.unpackReference(parentRepeat.getBind()));
            TreeReference childBind = FormInstance.unpackReference(child.getBind());
            
            //check if current binding is within scope of repeat binding
            if (!repeatBind.isParentOf(childBind, false)) {
                //catch <repeat nodeset="/a/b"><input ref="/a/c" /></repeat>: repeat question is not a child of the repeated node
                throw new XFormParseException("<repeat> member's binding [" + childBind.toString() + "] is not a descendant of <repeat> binding [" + repeatBind.toString() + "]!");
            } else if (repeatBind.equals(childBind) && isRepeat) {
                //catch <repeat nodeset="/a/b"><repeat nodeset="/a/b">...</repeat></repeat> (<repeat nodeset="/a/b"><input ref="/a/b" /></repeat> is ok)
                throw new XFormParseException("child <repeat>s [" + childBind.toString() + "] cannot bind to the same node as their parent <repeat>; only questions/groups can");
            }
            
            //check that, in the instance, current node is not within the scope of any closer repeat binding
            //build a list of all the node's instance ancestors
            Vector<TreeElement> repeatAncestry = new Vector<TreeElement>();
            TreeElement repeatNode = (repeatTree == null ? null : repeatTree.getRoot());
            if (repeatNode != null) {
                repeatAncestry.addElement(repeatNode);            
                for (int j = 1; j < childBind.size(); j++) {
                    repeatNode = repeatNode.getChild(childBind.getName(j), 0);
                    if (repeatNode != null) {
                        repeatAncestry.addElement(repeatNode);
                    } else {
                        break;
                    }
                }
            }
            //check that no nodes between the parent repeat and the target are repeatable
            for (int k = repeatBind.size(); k < childBind.size(); k++) {
                TreeElement rChild = (k < repeatAncestry.size() ? repeatAncestry.elementAt(k) : null);
                boolean repeatable = (rChild == null ? false : rChild.isRepeatable());
                if (repeatable && !(k == childBind.size() - 1 && isRepeat)) {
                    //catch <repeat nodeset="/a/b"><input ref="/a/b/c/d" /></repeat>...<repeat nodeset="/a/b/c">...</repeat>:
                    //  question's/group's/repeat's most immediate repeat parent in the instance is not its most immediate repeat parent in the form def
                    throw new XFormParseException("<repeat> member's binding [" + childBind.toString() + "] is within the scope of a <repeat> that is not its closest containing <repeat>!");
                }
            }

            verifyRepeatMemberBindings(child, instance, (isRepeat ? (GroupDef)child : parentRepeat));
        }
    }
    
    private void verifyItemsetBindings (FormInstance instance) {
        for (int i = 0; i < itemsets.size(); i++) {
            ItemsetBinding itemset = itemsets.elementAt(i);
            
            //check proper parent/child relationship
            if (!itemset.nodesetRef.isParentOf(itemset.labelRef, false)) {
                throw new XFormParseException("itemset nodeset ref is not a parent of label ref");
            } else if (itemset.copyRef != null && !itemset.nodesetRef.isParentOf(itemset.copyRef, false)) {
                throw new XFormParseException("itemset nodeset ref is not a parent of copy ref");
            } else if (itemset.valueRef != null && !itemset.nodesetRef.isParentOf(itemset.valueRef, false)) {
                throw new XFormParseException("itemset nodeset ref is not a parent of value ref");
            }

            //make sure the labelref is tested against the right instance
            //check if it's not the main instance
            DataInstance fi = null;
            if(itemset.labelRef.getInstanceName()!= null)
            {
                fi = _f.getNonMainInstance(itemset.labelRef.getInstanceName());
                if(fi == null)
                {
                    throw new XFormParseException("Instance: "+ itemset.labelRef.getInstanceName() + " Does not exists");
                }
            }
            else
            {
                fi = instance;
            }

            //If the data instance's structure isn't available until the form is entered, we can't really proceed further 
            //with consistency/sanity checks, so just bail.
            if(fi.isRuntimeEvaluated()) {
                return;
            }
            
            if(fi.getTemplatePath(itemset.labelRef) == null)
            {
                throw new XFormParseException("<label> node for itemset doesn't exist! [" + itemset.labelRef + "]");
            }
            /****  NOT SURE WHAT A COPYREF DOES OR IS, SO I'M NOT CHECKING FOR IT
            else if (itemset.copyRef != null && instance.getTemplatePath(itemset.copyRef) == null) {
                throw new XFormParseException("<copy> node for itemset doesn't exist! [" + itemset.copyRef + "]");
            } 
            ****/
            //check value nodes exist
            else if (itemset.valueRef != null && fi.getTemplatePath(itemset.valueRef) == null) {
                throw new XFormParseException("<value> node for itemset doesn't exist! [" + itemset.valueRef + "]");
            }
        }
    }
    
    private void verifyItemsetSrcDstCompatibility (FormInstance instance) {
        for (int i = 0; i < itemsets.size(); i++) {
            ItemsetBinding itemset = itemsets.elementAt(i);

            boolean destRepeatable = (instance.getTemplate(itemset.getDestRef()) != null);
            if (itemset.copyMode) {
                if (!destRepeatable) {
                    throw new XFormParseException("itemset copies to node(s) which are not repeatable");
                }
                
                //validate homogeneity between src and dst nodes
                TreeElement srcNode = instance.getTemplatePath(itemset.copyRef);
                TreeElement dstNode = instance.getTemplate(itemset.getDestRef());
                
                if (!FormInstance.isHomogeneous(srcNode, dstNode)) {
                    reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE,
                    "Your itemset source [" + srcNode.getRef().toString() + "] and dest [" + dstNode.getRef().toString() +
                            "] of appear to be incompatible!", null);
                }
                
                //TODO: i feel like, in theory, i should additionally check that the repeatable children of src and dst
                //match up (Achild is repeatable <--> Bchild is repeatable). isHomogeneous doesn't check this. but i'm
                //hard-pressed to think of scenarios where this would actually cause problems
            } else {
                if (destRepeatable) {
                    throw new XFormParseException("itemset sets value on repeatable nodes");
                }
            }
        }
    }
    
    private void applyInstanceProperties (FormInstance instance) {
        for (int i = 0; i < bindings.size(); i++) {
            DataBinding bind = bindings.elementAt(i);
            TreeReference ref = FormInstance.unpackReference(bind.getReference());
            Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref, true);
            
            if (nodes.size() > 0) {
                attachBindGeneral(bind);
            }
            for (int j = 0; j < nodes.size(); j++) {
                TreeReference nref = nodes.elementAt(j);
                attachBind(instance.resolveReference(nref), bind);    
            }                
        }
        
        applyControlProperties(instance);
    }    
        
    private static void attachBindGeneral (DataBinding bind) {
        TreeReference ref = FormInstance.unpackReference(bind.getReference());
        
        if (bind.relevancyCondition != null) {
            bind.relevancyCondition.addTarget(ref);
        }
        if (bind.requiredCondition != null) {
            bind.requiredCondition.addTarget(ref);
        }
        if (bind.readonlyCondition != null) {
            bind.readonlyCondition.addTarget(ref);
        }
        if (bind.calculate != null) {
            bind.calculate.addTarget(ref);
        }
    }
    
    private static void attachBind(TreeElement node, DataBinding bind) {
        node.setDataType(bind.getDataType());
            
        if (bind.relevancyCondition == null) {
            node.setRelevant(bind.relevantAbsolute);
        }
        if (bind.requiredCondition == null) {
            node.setRequired(bind.requiredAbsolute);
        }    
        if (bind.readonlyCondition == null) {
            node.setEnabled(!bind.readonlyAbsolute);
        }
        if (bind.constraint != null) {
            node.setConstraint(new Constraint(bind.constraint, bind.constraintMessage));
        }
            
        node.setPreloadHandler(bind.getPreload());
        node.setPreloadParams(bind.getPreloadParams());
    }
    
    //apply properties to instance nodes that are determined by controls bound to those nodes
    //this should make you feel slightly dirty, but it allows us to be somewhat forgiving with the form
    //(e.g., a select question bound to a 'text' type node) 
    private void applyControlProperties (FormInstance instance) {
        for (int h = 0; h < 2; h++) {
            Vector<TreeReference> selectRefs = (h == 0 ? selectOnes : selectMultis);
            int type = (h == 0 ? Constants.DATATYPE_CHOICE : Constants.DATATYPE_CHOICE_LIST);

            for (int i = 0; i < selectRefs.size(); i++) {
                TreeReference ref = selectRefs.elementAt(i);
                Vector<TreeReference> nodes = new EvaluationContext(instance).expandReference(ref, true);
                for (int j = 0; j < nodes.size(); j++) {
                    TreeElement node = instance.resolveReference(nodes.elementAt(j));
                    if (node.getDataType() == Constants.DATATYPE_CHOICE || node.getDataType() == Constants.DATATYPE_CHOICE_LIST) {
                        //do nothing
                    } else if (node.getDataType() == Constants.DATATYPE_NULL || node.getDataType() == Constants.DATATYPE_TEXT) {
                        node.setDataType(type);
                    } else {
                        reporter.warning(XFormParserReporter.TYPE_INVALID_STRUCTURE, 
                                "Multiple choice question " + ref.toString() + " appears to have data type that is incompatible with selection", null);
                    }
                }
            }
        }
    }
    
    //TODO: hook here for turning sub-trees into complex IAnswerData objects (like for immunizations)
    //FIXME: the 'ref' and FormDef parameters (along with the helper function above that initializes them) are only needed so that we
    //can fetch QuestionDefs bound to the given node, as the QuestionDef reference is needed to properly represent answers
    //to select questions. obviously, we want to fix this.
    private static void loadInstanceData (Element node, TreeElement cur, FormDef f) {        
        int numChildren = node.getChildCount();        
        boolean hasElements = false;
        for (int i = 0; i < numChildren; i++) {
            if (node.getType(i) == Node.ELEMENT) {
                hasElements = true;
                break;
            }
        }

        if (hasElements) {
            Hashtable<String, Integer> multiplicities = new Hashtable<String, Integer>(); //stores max multiplicity seen for a given node name thus far
            for (int i = 0; i < numChildren; i++) {
                if (node.getType(i) == Node.ELEMENT) {
                    Element child = node.getElement(i);
                    
                    String name = child.getName();
                    int index;
                    boolean isTemplate = (child.getAttributeValue(NAMESPACE_JAVAROSA, "template") != null);
                    
                    if (isTemplate) {
                        index = TreeReference.INDEX_TEMPLATE;
                    } else {
                        //update multiplicity counter
                        Integer mult = multiplicities.get(name);
                        index = (mult == null ? 0 : mult.intValue() + 1);
                        multiplicities.put(name, DataUtil.integer(index));
                    }
                    
                    loadInstanceData(child, cur.getChild(name, index), f);
                }
            }    
        } else {
            String text = getXMLText(node, true);
            if (text != null && text.trim().length() > 0) { //ignore text that is only whitespace
                //TODO: custom data types? modelPrototypes?
                cur.setValue(AnswerDataFactory.templateByDataType(cur.getDataType()).cast(new UncastData(text.trim())));
            }
        }        
    }
    
    //find a questiondef that binds to ref, if the data type is a 'select' question type
    public static QuestionDef ghettoGetQuestionDef (int dataType, FormDef f, TreeReference ref) {
        if (dataType == Constants.DATATYPE_CHOICE || dataType == Constants.DATATYPE_CHOICE_LIST) {
            return FormDef.findQuestionByRef(ref, f);
        } else {
            return null;
        }
    }
    
    private void checkDependencyCycles () {
        Vector vertices = new Vector();
        Vector edges = new Vector();
        
        //build graph
        for (Enumeration e = _f.triggerIndex.keys(); e.hasMoreElements(); ) {
            TreeReference trigger = (TreeReference)e.nextElement();
            if (!vertices.contains(trigger))
                vertices.addElement(trigger);
            
            Vector triggered = (Vector)_f.triggerIndex.get(trigger);
            Vector targets = new Vector();
            for (int i = 0; i < triggered.size(); i++) {
                Triggerable t = (Triggerable)triggered.elementAt(i);
                for (int j = 0; j < t.getTargets().size(); j++) {
                    TreeReference target = (TreeReference)t.getTargets().elementAt(j);
                    if (!targets.contains(target))
                        targets.addElement(target);
                }
            }
            
            for (int i = 0; i < targets.size(); i++) {
                TreeReference target = (TreeReference)targets.elementAt(i);
                if (!vertices.contains(target))
                    vertices.addElement(target);
                
                TreeReference[] edge = {trigger, target};
                edges.addElement(edge);
            }
        }
        
        //find cycles
        boolean acyclic = true;
        while (vertices.size() > 0) {
            //determine leaf nodes
            Vector leaves = new Vector();
            for (int i = 0; i < vertices.size(); i++) {
                leaves.addElement(vertices.elementAt(i));
            }
            for (int i = 0; i < edges.size(); i++) {
                TreeReference[] edge = (TreeReference[])edges.elementAt(i);
                leaves.removeElement(edge[0]);
            }
            
            //if no leaf nodes while graph still has nodes, graph has cycles
            if (leaves.size() == 0) {
                acyclic = false;
                break;
            }

            //remove leaf nodes and edges pointing to them
            for (int i = 0; i < leaves.size(); i++) {
                TreeReference leaf = (TreeReference)leaves.elementAt(i);
                vertices.removeElement(leaf);
            }            
            for (int i = edges.size() - 1; i >= 0; i--) {
                TreeReference[] edge = (TreeReference[])edges.elementAt(i);
                if (leaves.contains(edge[1]))
                    edges.removeElementAt(i);
            }
        }
        
        if (!acyclic) {
            String errorMessage = "XPath Dependency Cycle:\n";
            for (int i = 0; i < edges.size(); i++) {
                TreeReference[] edge = (TreeReference[])edges.elementAt(i);
                errorMessage += edge[0].toString() + " => " + edge[1].toString() + "\n";
            }
            reporter.error(errorMessage);
            
            throw new RuntimeException("Dependency cycles amongst the xpath expressions in relevant/calculate");
        }
    }
    
    public void loadXmlInstance(FormDef f, Reader xmlReader) throws IOException {
        loadXmlInstance(f, getXMLDocument(xmlReader));
    }
    
    /**
     * Load a compatible xml instance into FormDef f
     * 
     * call before f.initialize()!
     */
    public static void loadXmlInstance(FormDef f, Document xmlInst) {
        TreeElement savedRoot = XFormParser.restoreDataModel(xmlInst, null).getRoot();
        TreeElement templateRoot = f.getMainInstance().getRoot().deepCopy(true);

        // weak check for matching forms
        // TODO: should check that namespaces match?
        if (!savedRoot.getName().equals(templateRoot.getName()) || savedRoot.getMult() != 0) {
            throw new RuntimeException("Saved form instance does not match template form definition");
        }

        // populate the data model
        TreeReference tr = TreeReference.rootRef();
        tr.add(templateRoot.getName(), TreeReference.INDEX_UNBOUND);
        templateRoot.populate(savedRoot, f);

        // populated model to current form
        f.getMainInstance().setRoot(templateRoot);
      
        // if the new instance is inserted into the formdef before f.initialize() is called, this
        // locale refresh is unnecessary
        //   Localizer loc = f.getLocalizer();
        //   if (loc != null) {
        //       f.localeChanged(loc.getLocale(), loc);
        //     }
    }
            
    //returns data type corresponding to type string; doesn't handle defaulting to 'text' if type unrecognized/unknown
    private int getDataType(String type) {
        int dataType = Constants.DATATYPE_NULL;
        
        if (type != null) {
            //cheap out and ignore namespace
            if (type.indexOf(":") != -1) {
                type = type.substring(type.indexOf(":") + 1);
            }
            
            if (typeMappings.containsKey(type)) {
                dataType = ((Integer)typeMappings.get(type)).intValue();                
            } else {
                dataType = Constants.DATATYPE_UNSUPPORTED;
                reporter.warning(XFormParserReporter.TYPE_ERROR_PRONE, "unrecognized data type [" + type + "]", null);
            }
        }
        
        return dataType;
    }

    public static void addModelPrototype(int type, TreeElement element) {
        modelPrototypes.addNewPrototype(String.valueOf(type), element.getClass());
    }

    public static void addDataType (String type, int dataType) {
        typeMappings.put(type, DataUtil.integer(dataType));
    }
        
    public static void registerControlType(String type, final int typeId) {
        IElementHandler newHandler = new IElementHandler() {
            public void handle (XFormParser p, Element e, Object parent) { p.parseControl((IFormElement)parent, e, typeId); } };
        topLevelHandlers.put(type, newHandler);
        groupLevelHandlers.put(type, newHandler);
    }

    public static void registerHandler(String type, IElementHandler handler) {
        topLevelHandlers.put(type, handler);
        groupLevelHandlers.put(type, handler);
    }
    
    /**
     * Let parser know how to handle a given action.
     * @param type Name of tag.
     * @param handler Handler for tag.
     */
    public static void registerStructuredAction(String type, IElementHandler handler) {
        structuredActions.put(type, handler);
    }

    /**
     * Notify parser about a node that will later be relevant to an action.
     * @param target
     */
    public void registerActionTarget(TreeReference target) {
        actionTargets.addElement(target);
    }

    public static String getXMLText (Node n, boolean trim) {
        return (n.getChildCount() == 0 ? null : getXMLText(n, 0, trim));
    }

    /**
    * reads all subsequent text nodes and returns the combined string
    * needed because escape sequences are parsed into consecutive text nodes
    * e.g. "abc&amp;123" --> (abc)(&)(123)
    **/
    public static String getXMLText (Node node, int i, boolean trim) {
        StringBuffer strBuff = null;

        String text = node.getText(i);
        if (text == null)
            return null;

        for (i++; i < node.getChildCount() && node.getType(i) == Node.TEXT; i++) {
            if (strBuff == null)
                strBuff = new StringBuffer(text);

            strBuff.append(node.getText(i));
        }
        if (strBuff != null)
            text = strBuff.toString();

        if (trim)
            text = text.trim();

        return text;
    }
    
    public static FormInstance restoreDataModel (InputStream input, Class restorableType) throws IOException {
        Document doc = getXMLDocument(new InputStreamReader(input));
        if (doc == null) {
            throw new RuntimeException("syntax error in XML instance; could not parse");
        }
        return restoreDataModel(doc, restorableType);
    }
    
    public static FormInstance restoreDataModel (Document doc, Class restorableType) {
        Restorable r = (restorableType != null ? (Restorable)PrototypeFactory.getInstance(restorableType) : null);
        
        Element e = doc.getRootElement();
        
        TreeElement te = buildInstanceStructure(e, null);
        FormInstance dm = new FormInstance(te);
        loadNamespaces(e, dm);
        if (r != null) {
            RestoreUtils.templateData(r, dm, null);
        }
        loadInstanceData(e, te, null);
        
        return dm;
    }
    
    public static FormInstance restoreDataModel (byte[] data, Class restorableType) {
        try {
            return restoreDataModel(new ByteArrayInputStream(data), restorableType);
        } catch (IOException e) {
            e.printStackTrace();
            throw new XFormParseException("Bad parsing from byte array " + e.getMessage());
        }
    }
    
    public static String getVagueLocation(Element e) {
        String path = e.getName();
        Element walker = e;
        while(walker != null) {
            Node n = walker.getParent();
            if(n instanceof Element) {
                walker = (Element)n;
                String step = walker.getName();
                for(int i = 0; i <  walker.getAttributeCount() ; ++i) {
                    step += "[@" +walker.getAttributeName(i) + "=";
                    step += walker.getAttributeValue(i) + "]";
                }
                path = step + "/" + path;
            } else {
                walker = null;
                path = "/" + path;
            }
        }
        
        String elementString = getVagueElementPrintout(e, 2);
        
        String fullmsg = "\n    Problem found at nodeset: " + path;
        fullmsg += "\n    With element " + elementString + "\n";
        return fullmsg;
    }
    
    public static String getVagueElementPrintout(Element e, int maxDepth) {
        String elementString = "<" + e.getName();
        for(int i = 0; i <  e.getAttributeCount() ; ++i) {
            elementString += " " + e.getAttributeName(i) + "=\"";
            elementString += e.getAttributeValue(i) + "\"";
        }
        if(e.getChildCount() > 0) {
            elementString += ">";
            if(e.getType(0) ==Element.ELEMENT) {
                if(maxDepth > 0) {
                    elementString += getVagueElementPrintout((Element)e.getChild(0),maxDepth -1);
                } else {
                    elementString += "...";
                }
            }
        } else {
            elementString += "/>";
        }
        return elementString;
    }

    public void setStringCache(Interner<String> stringCache) {
        this.stringCache = stringCache;
    }
}
