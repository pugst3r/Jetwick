/**
 * Copyright (C) 2010 Peter Karich <jetwick_@_pannous_._info>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.jetwick.ui;

import com.google.inject.Inject;
import de.jetwick.tw.MyTweetGrabber;
import de.jetwick.tw.TwitterSearch;
import de.jetwick.tw.queue.QueueThread;
import de.jetwick.ui.util.MyAutoCompleteTextField;
import de.jetwick.ui.util.SelectOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.form.AjaxSubmitLink;
import org.apache.wicket.extensions.ajax.markup.html.autocomplete.AutoCompleteSettings;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.util.ListModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.progressbar.ProgressBar;
import org.wicketstuff.progressbar.Progression;
import org.wicketstuff.progressbar.ProgressionModel;

/**
 *
 * @author Peter Karich, peat_hal 'at' users 'dot' sourceforge 'dot' net
 */
public class GrabTweetsDialog extends Panel {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private TextField field;
    private DropDownChoice<SelectOption<Integer, String>> choices;
    private String userName;
    private transient QueueThread pkg;
    private boolean started = false;
    @Inject
    private MyTweetGrabber grabber;

    public GrabTweetsDialog(String id, String user) {
        super(id);

        this.userName = user;
        final Form form = new Form("grabForm");
        final ProgressBar bar = new ProgressBar("bar", new ProgressionModel() {

            @Override
            protected Progression getProgression() {
                if (pkg == null)
                    return new Progression(0);

                return new Progression(pkg.getProgress());
            }
        }) {

            @Override
            protected void onFinished(AjaxRequestTarget target) {
                logger.info("finished: " + pkg.getProgress() + " canceled:" + pkg.isCanceled());

                if (pkg.getException() != null) {
                    logger.error("Error while storing archive", pkg.getException());
                    String msg = TwitterSearch.getMessage(pkg.getException());
                    if (msg.length() > 0)
                        error(msg);
                    else
                        error("Couldn't process your request. Please contact admin "
                                + "or twitter.com/jetwick if problem remains!");
                } else
                    info(grabber.getTweetCount() + " tweets were stored for " + grabber.getUserName()
                            + ". In approx. 5min they will be searchable.");

                GrabTweetsDialog.this.updateAfterAjax(target);
                GrabTweetsDialog.this.onClose(target);
                started = false;
            }
        };
        form.add(bar);
        form.add(new AjaxSubmitLink("ajaxSubmit") {

            @Override
            protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
                if (!started) {
                    started = true;
                    bar.start(target);
                    final String userName = getUsername();
                    if (getMaxTweets() > 0) {
                        grabber.setUserName(userName);
                        grabber.setTweetsCount(getMaxTweets());
                        grabber.setTwitterSearch(getTwitterSearch());
                        pkg = grabber.queueArchiving();
                        new Thread(pkg).start();
                    }
                } else {
                    info("You've already queued a job.");
                    GrabTweetsDialog.this.updateAfterAjax(target);
                }
            }
        });
        add(form);
        AutoCompleteSettings config = new AutoCompleteSettings().setUseHideShowCoveredIEFix(false);
        config.setThrottleDelay(200);
        form.add(field = new MyAutoCompleteTextField("userName", new PropertyModel(this, "userName"), config) {

            @Override
            protected Iterator getChoices(String input) {
                return GrabTweetsDialog.this.getUserChoices(input).iterator();
            }

            @Override
            protected void onSelectionChange(AjaxRequestTarget target, String newValue) {
                // skip
            }
        });
        choices = new DropDownChoice<SelectOption<Integer, String>>("tweetMenu",
                new ListModel(), Arrays.asList(
                new SelectOption<Integer, String>(200, "200"),
                new SelectOption<Integer, String>(1000, "1000"),
                new SelectOption<Integer, String>(3200, "MAX")));
        choices.getModel().setObject(choices.getChoices().get(0));
        choices.setNullValid(false);
        form.add(choices);
    }

    protected TwitterSearch getTwitterSearch() {
        throw new RuntimeException();
    }

    protected Collection<String> getUserChoices(String input) {
        throw new RuntimeException();
    }

    /**
     * Called to updated super components
     */
    public void updateAfterAjax(AjaxRequestTarget target) {
    }

    /**
     * Called before closing this dialog
     */
    public void onClose(AjaxRequestTarget target) {
    }

    public String getUsername() {
        return (String) field.getModelObject();
    }

    public int getMaxTweets() {
        try {
            SelectOption<Integer, String> choice = choices.getModelObject();
            return choice.getKey();
        } catch (Exception ex) {
            logger.error("Error while getting tweet page number", ex);
        }
        return 0;
    }

    public void interruptGrabber() {
        started = false;
        if (pkg != null)
            pkg.doCancel();
    }
}
