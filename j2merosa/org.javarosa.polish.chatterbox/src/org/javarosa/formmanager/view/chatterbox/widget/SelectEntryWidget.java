package org.javarosa.formmanager.view.chatterbox.widget;

import javax.microedition.lcdui.Image;

import org.javarosa.core.model.QuestionDef;

import de.enough.polish.ui.ChoiceGroup;
import de.enough.polish.ui.ChoiceItem;
import de.enough.polish.ui.Container;
import de.enough.polish.ui.Item;

/**
 * The base widget for multi and single choice selections.
 * 
 * NOTE: This class has a number of Hacks that I've made after rooting through
 * the j2me polish source. If polish is behaving unpredictably, it is possibly because
 * of conflicting changes in new Polish versions with this code. I have outlined
 * the latest versions of polish in which the changes appear to work.
 * 
 * Questions should be directed to csims@dimagi.com
 * 
 * @author Clayton Sims
 * @date Feb 16, 2009 
 *
 */
public abstract class SelectEntryWidget extends ExpandedWidget {
	private int style;
	protected QuestionDef question;
	
	private ChoiceGroup choicegroup;
	
	public SelectEntryWidget (int style) {
		this.style = style;
	}
	
	protected Item getEntryWidget (QuestionDef question) {
		this.question = question;
		
		ChoiceGroup cg = new ChoiceGroup("", style) {
			
			/** Hack #1 & Hack #3**/
			// j2me polish refuses to produce events in the case where select items
			// are already selected. This code intercepts key presses that toggle
			// selection, and clear any existing selection to prevent the supression
			// of the appropriate commands.
			//
			// Hack #3 is due to the fact that multiple choice items won't fire updates
			// unless they haven't been selected by default. The return true here for them
			// ensures that the system knows that fires on multi-select always signify
			// a capture.
			//
			// NOTE: These changes are only necessary for Polish versions > 2.0.5 as far
			// as I can tell. I have tested them on 2.0.4 and 2.0.7 and the changes
			// were compatibly with both
			protected boolean handleKeyReleased(int keyCode, int gameAction) {
				boolean gameActionIsFire = getScreen().isGameActionFire(
						keyCode, gameAction);
				if (gameActionIsFire) {
					ChoiceItem choiceItem = (ChoiceItem) this.focusedItem;
					if(this.choiceType != ChoiceGroup.MULTIPLE) {
						//Hack #1
						choiceItem.isSelected = false;
					}
				}
				boolean superReturn = super.handleKeyReleased(keyCode, gameAction);
				if(gameActionIsFire && this.choiceType == ChoiceGroup.MULTIPLE) {
					//Hack #3
					return true;
				} else {
					return superReturn;
				}
			}
			
			/** Hack #2 **/
			//This is a slight UI hack that is in place to make the choicegroup properly
			//intercept 'up' and 'down' inputs. Essentially Polish is very broken when it comes
			//to scrolling nested containers, and this function properly takes the parent (Widget) position
			//into account as well as the choicegroup's
			//
			// I have tested this change with Polish 2.0.4 on Nokia Phones and the emulators and it works
			// correctly. It also works correctly on Polish 2.0.7 on the emulator, but I have not attempted
			// to use it on a real nokia phone.
			public int getRelativeScrollYOffset() {
				if (!this.enableScrolling && this.parent instanceof Container) {
					
					// Clayton Sims - Feb 9, 2009 : Had to go through and modify this code again.
					// The offsets are now accumulated through all of the parent containers, not just
					// one.
					Item walker = this.parent;
					int offset = 0;
					
					//Walk our parent containers and accumulate their offsets.
					while(walker instanceof Container) {
						offset += walker.relativeY;
						walker = walker.getParent();
					}
					//This line here (The + offest part) is the fix.
					return ((Container)this.parent).getScrollYOffset() + this.relativeY + offset;
					
					// Clayton Sims - Feb 10, 2009 : Rolled back because it doesn't work on the 3110c, apparently!
					// Fixing soon.
					//return ((Container)this.parent).getScrollYOffset() + this.relativeY + this.parent.relativeY;
				}
				int offset = this.targetYOffset;
				//#ifdef polish.css.scroll-mode
					if (!this.scrollSmooth) {
						offset = this.yOffset;
					}
				//#endif
				return offset;
			}
		};
		for (int i = 0; i < question.getSelectItems().size(); i++){
			cg.append("", null);
		}
		
		this.choicegroup = cg;
		
		return cg;
	}

	protected ChoiceGroup choiceGroup () {
		//return (ChoiceGroup)entryWidget;
		return this.choicegroup;
	}

	protected void updateWidget (QuestionDef question) {
		for (int i = 0; i < choiceGroup().size(); i++) {
			choiceGroup().getItem(i).setText((String)question.getSelectItems().keyAt(i));
		}
	}
}