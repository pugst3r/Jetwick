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
package de.jetwick.tw;

import com.google.inject.Inject;
import de.jetwick.data.JTweet;
import de.jetwick.es.ElasticTweetSearch;
import de.jetwick.tw.queue.TweetPackage;
import de.jetwick.util.MyDate;
import de.jetwick.util.StopWatch;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.BlockingQueue;
import org.elasticsearch.action.admin.indices.optimize.OptimizeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stores the tweets from the queue into the dbHelper and solr
 * 
 * @author Peter Karich, peat_hal 'at' users 'dot' sourceforge 'dot' net
 */
public class TweetConsumer extends MyThread {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private BlockingQueue<TweetPackage> tweetPackages;
    // collect at least those tweets before feeding
    private int tweetBatchSize = 1;
    private long tweetBatchTime = 60 * 1000;
    private long lastFeed = System.currentTimeMillis();
    // do not optimize per default
    private int optimizeToSegmentsAfterUpdate = -1;
    private long optimizeInterval = -1;
    // optimize should not happen directly after start of tweet consumer / collector!
    private long lastOptimizeTime = System.currentTimeMillis();
    private StopWatch longTime;
    private StopWatch currentTime;
    @Inject
    protected ElasticTweetSearch tweetSearch;
    private int removeDays = 8;
    private long receivedTweets = 0;
    private long indexedTweets = 0;

    public TweetConsumer() {
        super("tweet-consumer");
    }

    @Override
    public void run() {
        logger.info("tweets per solr session:" + tweetBatchSize);
        Collection<JTweet> tweetSet = new LinkedHashSet<JTweet>();
            
        while (!isInterrupted()) {            
            try {
                tweetSet.addAll(tweetPackages.take().getTweets());
            } catch (InterruptedException ex) {
                break;
            }

            if (longTime == null)
                longTime = new StopWatch("alltime");
            
            if (tweetSet.size() < tweetBatchSize && System.currentTimeMillis() - lastFeed < tweetBatchTime) {
                // slow down calcNumberOfTweets calculation
                if(!myWait(5))
                    break;
                continue;
            }
                        
            if (tweetSet.isEmpty())
                continue;

            lastFeed = System.currentTimeMillis();
            currentTime = new StopWatch("");
            longTime.start();
            currentTime.start();
            updateTweets(tweetSet);            
            currentTime.stop();
            longTime.stop();
            indexedTweets += tweetSet.size();
            
//            String str = "[es] indexed:";
//            for (TweetPackage pkg : donePackages) {
//                str += pkg.getName() + ", age:" + pkg.getAgeInSeconds() + "s, ";
//            }
//            logger.info(str);
            
            float tweetsPerSec = indexedTweets / (longTime.getTime() / 1000.0f);
            String str = "[es] " + currentTime.toString() + "\t tweets/s:" + tweetsPerSec
                    + "\t curr indexedTw:" + tweetSet.size() + " all indexedTw:" + indexedTweets + "\t all receivedTw:" + receivedTweets;

            tweetSet.clear();
            long time = System.currentTimeMillis();
            if (optimizeInterval > 0)
                str += "; next optimize in: " + (optimizeInterval - (time - lastOptimizeTime)) / 3600f / 1000f + "h ";

            logger.info(str);
            if (optimizeToSegmentsAfterUpdate > 0) {
                if (optimizeInterval > 0 && time - lastOptimizeTime >= optimizeInterval) {
                    lastOptimizeTime = time;
                    OptimizeResponse orsp = tweetSearch.optimize(tweetSearch.getIndexName(), optimizeToSegmentsAfterUpdate);
                    logger.info("[es] optimized twindex to segments:" + optimizeToSegmentsAfterUpdate);
                }
            }
        }
        logger.info(getName() + " finished");
    }

    public void updateTweets(Collection<JTweet> tweetSet) {      
        int maxTrials = 1;
        for (int trial = 1; trial <= maxTrials; trial++) {
            try {                           
                MyDate removeUntil = new MyDate().minusDays(removeDays);
                boolean performDelete = removeUntil._getHoursOfDay() == 0;
                tweetSearch.update(tweetSet, removeUntil.toDate(), performDelete);
                receivedTweets += tweetSet.size();                
            } catch (Exception ex) {
                logger.error("trial " + trial + ". couldn't update "
                        + tweetSet.size() + " tweets. now wait and try again", ex);
                if (trial == maxTrials)
                    break;

                myWait(5);
            }
        }
    }

    public void setTweetSearch(ElasticTweetSearch tweetSearch) {
        this.tweetSearch = tweetSearch;
    }

    public void setRemoveDays(int solrRemoveDays) {
        removeDays = solrRemoveDays;
    }

    public void setReadingQueue(BlockingQueue<TweetPackage> queue) {
        tweetPackages = queue;
    }

    public void setTweetBatchSize(int tweetBatchSize) {
        this.tweetBatchSize = tweetBatchSize;
    }

    /**
     * @param optimizeInterval     
     *        in the form of 2     (i.e. every 2 hours)
     */
    public void setOptimizeInterval(String optimizeStr) {
        optimizeInterval = -1;

        if (optimizeStr == null)
            return;

        optimizeStr = optimizeStr.trim();
        try {
            int index = optimizeStr.indexOf(":");
            if (index >= 0)
                logger.warn("Not supported ony longer because it can be that optimized is triggered several times!");
            else
                optimizeInterval = Long.parseLong(optimizeStr) * 3600 * 1000;
        } catch (Exception ex) {
            logger.warn("Optimization disabled! " + ex.getLocalizedMessage());
        }
    }

    public void setOptimizeToSegmentsAfterUpdate(int optimizeToSegmentsAfterUpdate) {
        this.optimizeToSegmentsAfterUpdate = optimizeToSegmentsAfterUpdate;
    }
}
