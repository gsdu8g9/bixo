/*
 * Copyright (c) 1997-2009 101tec Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy 
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights 
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell 
 * copies of the Software, and to permit persons to whom the Software is 
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package bixo.fetcher.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.http.HttpStatus;

import bixo.datum.UrlDatum;
import bixo.exceptions.HttpFetchException;
import bixo.exceptions.IOFetchException;
import bixo.fetcher.http.IHttpFetcher;
import bixo.fetcher.http.SimpleHttpFetcher;
import bixo.fetcher.http.SimpleRobotRules;
import bixo.utils.DomainNames;
import bixo.utils.GroupingKey;

/**
 * Generate a key that consists of the URL's IP address or PLD, followed by a '-', followed
 * by the crawl delay interval specified by the appropriate robots.txt.
 * 
 * This results is a group of all URLs that can be fetched from the same server, at
 * the same rate. Though if grouping by PLD, this might not be the case, as xxx.domain.com
 * can go to a different server than yyy.domain.com.
 * 
 * If we get filtered, or robots.txt can't be fetched/parsed correctly, then set the
 * crawl delay value to one of the pre-defined status names for these cases, so the
 * processing pipe can efficiently update the URL status in the DB and skip further
 * processing. 
 */
@SuppressWarnings("serial")
public class SimpleGroupingKeyGenerator implements IGroupingKeyGenerator {

    private HashSet<String> _badHosts;
    private HashMap<String, SimpleRobotRules> _rules;
    private IHttpFetcher _robotsFetcher;
    private String _userAgent;
    private boolean _usePLD;
    
    public SimpleGroupingKeyGenerator(String userAgent) {
        this(userAgent, null, false);
    }
    
    // TODO KKr - have RobotRules as abstract class, and IRobotRulesParser as something that
    // can return RobotRules when given user agent/content or http response code. Then take
    // IRobotRulesParser as parameter here. Also want IHttpFetcher as input here, for testing.
    public SimpleGroupingKeyGenerator(String userAgent, IHttpFetcher robotsFetcher, boolean usePaidLevelDomain) {
        _userAgent = userAgent;
        _badHosts = new HashSet<String>();
        _rules = new HashMap<String, SimpleRobotRules>();
        _usePLD = usePaidLevelDomain;

        if (robotsFetcher == null) {
            _robotsFetcher = new SimpleHttpFetcher(1, userAgent);
        } else {
            _robotsFetcher = robotsFetcher;
        }
    }
    
    @Override
    public String getGroupingKey(UrlDatum urlDatum) throws IOException {
        String urlStr = urlDatum.getUrl();
        
        URL url;
        String host = null;
        InetAddress ia = null;
        
        try {
            url = new URL(urlStr);
            host = url.getHost().toLowerCase();
            if (!_usePLD) {
                if (_badHosts.contains(host)) {
                    return UNKNOWN_HOST_GROUPING_KEY;
                }

                ia = InetAddress.getByName(host);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Unable to parse url: " + urlStr, e);
        } catch (UnknownHostException e) {
            _badHosts.add(host);
            return UNKNOWN_HOST_GROUPING_KEY;
        }
        
        // Get the robots.txt for this domain
        SimpleRobotRules robotRules = _rules.get(host);
        if (robotRules == null) {
            String robotsUrl = null;

            try {
                // TODO KKr - do I need to worry about the https protocol here?
                robotsUrl = new URL(url.getProtocol(), host, url.getPort(), "/robots.txt").toExternalForm();
                byte[] robotsContent = _robotsFetcher.get(robotsUrl);
                robotRules = new SimpleRobotRules(_userAgent, robotsContent);
            } catch (HttpFetchException e) {
                robotRules = new SimpleRobotRules(e.getHttpStatus());
            } catch (IOFetchException e) {
                // Couldn't load robots.txt for some reason (e.g. ConnectTimeoutException), so
                // treat it like a server internal error case.
                robotRules = new SimpleRobotRules(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception e) {
                throw new RuntimeException("Exception handling robots.txt: " + robotsUrl, e);
            }

            // TODO KKr - have max size for this, so we don't chew up too much memory?
            _rules.put(host, robotRules);
        }
        
        if (robotRules.getDeferVisits()) {
            return DEFERRED_GROUPING_KEY;
        } else if (robotRules.isAllowed(urlStr)) {
            return GroupingKey.makeGroupingKey(_usePLD ? DomainNames.getPLD(host) : ia.getHostAddress(), robotRules.getCrawlDelay());
        } else {
            return BLOCKED_GROUPING_KEY;
        }
    }

}
