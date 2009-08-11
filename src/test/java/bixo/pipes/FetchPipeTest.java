package bixo.pipes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.mapred.JobConf;
import org.junit.Assert;
import org.junit.Test;

import bixo.config.FetcherPolicy;
import bixo.datum.BaseDatum;
import bixo.datum.FetchedDatum;
import bixo.datum.GroupedUrlDatum;
import bixo.datum.StatusDatum;
import bixo.datum.UrlDatum;
import bixo.datum.UrlStatus;
import bixo.fetcher.http.IHttpFetcher;
import bixo.fetcher.simulation.FakeHttpFetcher;
import bixo.fetcher.simulation.NullHttpFetcher;
import bixo.fetcher.util.IScoreGenerator;
import bixo.fetcher.util.LastFetchScoreGenerator;
import bixo.fetcher.util.SimpleGroupingKeyGenerator;
import bixo.operations.FetcherBuffer;
import cascading.CascadingTestCase;
import cascading.flow.Flow;
import cascading.flow.FlowConnector;
import cascading.pipe.Pipe;
import cascading.scheme.SequenceFile;
import cascading.tap.Hfs;
import cascading.tap.Lfs;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.TupleEntryIterator;
import cascading.util.Util;

public class FetchPipeTest extends CascadingTestCase {
    private static final long TEN_DAYS = 1000L * 60 * 60 * 24 * 10;
    private static final String USER_AGENT_FAKE_FETCHING = "user agent for fake fetching";
    
    @SuppressWarnings("serial")
    private static class SkippedScoreGenerator implements IScoreGenerator {

        @Override
        public double generateScore(GroupedUrlDatum urlTuple) throws IOException {
            return IScoreGenerator.SKIP_URL_SCORE;
        }
    }
    
    private Lfs makeInputData(int numDomains, int numPages) throws IOException {
        return makeInputData(numDomains, numPages, null);
    }
    
    @SuppressWarnings("unchecked")
    private Lfs makeInputData(int numDomains, int numPages, Map<String, Comparable> metaData) throws IOException {
        Fields sfFields = UrlDatum.FIELDS.append(BaseDatum.makeMetaDataFields(metaData));
        Lfs in = new Lfs(new SequenceFile(sfFields), "build/test-data/FetchPipeTest/in", true);
        TupleEntryCollector write = in.openForWrite(new JobConf());
        for (int i = 0; i < numDomains; i++) {
            for (int j = 0; j < numPages; j++) {
                UrlDatum url = new UrlDatum("http://domain-" + i + ".com/page-" + j + ".html?size=10", 0, 0, UrlStatus.UNFETCHED, metaData);
                Tuple tuple = url.toTuple();
                write.add(tuple);
            }
        }
        
        write.close();
        return in;
    }
    
    @Test
    public void testFetchPipe() throws Exception {
        Lfs in = makeInputData(100, 1);

        Pipe pipe = new Pipe("urlSource");
        SimpleGroupingKeyGenerator grouping = new SimpleGroupingKeyGenerator(USER_AGENT_FAKE_FETCHING, new NullHttpFetcher(), true);
        LastFetchScoreGenerator scoring = new LastFetchScoreGenerator(System.currentTimeMillis(), TEN_DAYS);
        IHttpFetcher fetcher = new FakeHttpFetcher(false, 10);
        FetchPipe fetchPipe = new FetchPipe(pipe, grouping, scoring, fetcher);
        
        String outputPath = "build/test-data/FetchPipeTest/testFetchPipe";
        Tap status = new Lfs(new SequenceFile(StatusDatum.FIELDS), outputPath + "/status", true);
        Tap content = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content", true);

        // Finally we can run it.
        FlowConnector flowConnector = new FlowConnector();
        Flow flow = flowConnector.connect(in, FetchPipe.makeSinkMap(status, content), fetchPipe);
        flow.complete();
        
        // Werify 100 fetched and 100 status entries were saved.
        Lfs validate = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content");
        TupleEntryIterator tupleEntryIterator = validate.openForRead(new JobConf());
        
        Fields metaDataFields = new Fields();
        int totalEntries = 0;
        while (tupleEntryIterator.hasNext()) {
            TupleEntry entry = tupleEntryIterator.next();
            totalEntries += 1;

            // Verify we can convert properly
            new FetchedDatum(entry, metaDataFields);
        }
        
        Assert.assertEquals(100, totalEntries);
        tupleEntryIterator.close();
        
        validate = new Lfs(new SequenceFile(StatusDatum.FIELDS), outputPath + "/status");
        tupleEntryIterator = validate.openForRead(new JobConf());
        totalEntries = 0;
        while (tupleEntryIterator.hasNext()) {
            TupleEntry entry = tupleEntryIterator.next();
            totalEntries += 1;

            // Verify we can convert properly
            StatusDatum sd = new StatusDatum(entry, metaDataFields);
            Assert.assertEquals(UrlStatus.FETCHED, sd.getStatus());
        }
        
        Assert.assertEquals(100, totalEntries);
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testMetaData() throws Exception {
        Map<String, Comparable> metaData = new HashMap<String, Comparable>();
        metaData.put("key", "value");
        Lfs in = makeInputData(1, 1, metaData);

        Pipe pipe = new Pipe("urlSource");
        IHttpFetcher fetcher = new FakeHttpFetcher(false, 10);
        SimpleGroupingKeyGenerator grouping = new SimpleGroupingKeyGenerator(USER_AGENT_FAKE_FETCHING, new NullHttpFetcher(), true);
        LastFetchScoreGenerator scoring = new LastFetchScoreGenerator(System.currentTimeMillis(), TEN_DAYS);
        FetchPipe fetchPipe = new FetchPipe(pipe, grouping, scoring, fetcher, new Fields("key"));
        
        String outputPath = "build/test-data/FetchPipeTest/dual";
        Fields contentFields = FetchedDatum.FIELDS.append(new Fields("key"));
        Tap content = new Hfs(new SequenceFile(contentFields), outputPath + "/content", true);

        // Finally we can run it.
        FlowConnector flowConnector = new FlowConnector();
        Flow flow = flowConnector.connect(in, FetchPipe.makeSinkMap(null, content), fetchPipe);
        flow.complete();
        
        Lfs validate = new Lfs(new SequenceFile(contentFields), outputPath + "/content");
        TupleEntryIterator tupleEntryIterator = validate.openForRead(new JobConf());
        
        int totalEntries = 0;
        while (tupleEntryIterator.hasNext()) {
            TupleEntry entry = tupleEntryIterator.next();
            totalEntries += 1;
            
            Assert.assertEquals(entry.size(), contentFields.size());
            String metaValue = entry.getString("key");
            Assert.assertNotNull(metaValue);
            Assert.assertEquals("value", metaValue);
        }
        
        Assert.assertEquals(1, totalEntries);
    }
    
    @Test
    public void testSkippingURLsByScore() throws Exception {
        Lfs in = makeInputData(1, 1);

        Pipe pipe = new Pipe("urlSource");
        IHttpFetcher fetcher = new FakeHttpFetcher(false, 1);
        SimpleGroupingKeyGenerator grouping = new SimpleGroupingKeyGenerator(USER_AGENT_FAKE_FETCHING, new NullHttpFetcher(), true);
        IScoreGenerator scoring = new SkippedScoreGenerator();
        FetchPipe fetchPipe = new FetchPipe(pipe, grouping, scoring, fetcher);
        
        String outputPath = "build/test-data/FetchPipeTest/out";
        Tap content = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content", true);
        
        // Finally we can run it.
        FlowConnector flowConnector = new FlowConnector();
        Flow flow = flowConnector.connect(in, FetchPipe.makeSinkMap(null, content), fetchPipe);
        flow.complete();
        
        Lfs validate = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content");
        TupleEntryIterator tupleEntryIterator = validate.openForRead(new JobConf());
        Assert.assertFalse(tupleEntryIterator.hasNext());
    }
    
    @Test
    public void testDurationLimitSimple() throws Exception {
        // Pretend like we have 10 URLs from the same domain
        Lfs in = makeInputData(1, 10);

        // Create the fetch pipe we'll use to process these fake URLs
        Pipe pipe = new Pipe("urlSource");
        IHttpFetcher fetcher = new FakeHttpFetcher(false, 1);
        SimpleGroupingKeyGenerator grouping = new SimpleGroupingKeyGenerator(USER_AGENT_FAKE_FETCHING, new NullHttpFetcher(), true);
        LastFetchScoreGenerator scoring = new LastFetchScoreGenerator(System.currentTimeMillis(), TEN_DAYS);
        FetchPipe fetchPipe = new FetchPipe(pipe, grouping, scoring, fetcher);

        // Create the output
        String outputPath = "build/test-data/FetchPipeTest/out";
        Tap content = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content", true);

        // Finally we can run it. Set up our prefs with a FetcherPolicy that has an end time of now,
        // which means we shouldn't fetch any URLs, but they should all be aborted.
        Properties properties = new Properties();
        FetcherPolicy defaultPolicy = new FetcherPolicy();
        defaultPolicy.setCrawlEndTime(System.currentTimeMillis());
        properties.put(FetcherBuffer.DEFAULT_FETCHER_POLICY_KEY, Util.serializeBase64(defaultPolicy));

        FlowConnector flowConnector = new FlowConnector(properties);
        Flow flow = flowConnector.connect(in, FetchPipe.makeSinkMap(null, content), fetchPipe);
        flow.complete();
        
        Lfs validate = new Lfs(new SequenceFile(FetchedDatum.FIELDS), outputPath + "/content");
        TupleEntryIterator tupleEntryIterator = validate.openForRead(new JobConf());
        Assert.assertFalse(tupleEntryIterator.hasNext());
    }
    

}
