/*
 * Copyright 2010 DTO Labs, Inc. (http://dtolabs.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
* PoliciesCache.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: Nov 16, 2010 11:26:12 AM
* 
*/
package com.dtolabs.rundeck.core.authorization.providers;

import org.apache.log4j.Logger;
import org.yaml.snakeyaml.parser.ParserException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;

/**
 * PoliciesCache retains PolicyDocument objects for inserted Files, and reloads them if file modification time changes.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class PoliciesCache implements Iterable<PolicyCollection> {
    static final long DIR_LIST_CHECK_DELAY = Long.getLong(PoliciesCache.class.getName()+".DirListCheckDelay", 60000);
    static final long FILE_CHECK_DELAY = Long.getLong(PoliciesCache.class.getName() + ".FileCheckDelay", 60000);
    private final static Logger logger = Logger.getLogger(PoliciesCache.class);
    
    static final FilenameFilter filenameFilter = new FilenameFilter() {
        public boolean accept(File dir, String name) {
            return name.endsWith(".aclpolicy");
        }
    };
    
    private Set<File> warned = new HashSet<File>();
    private Map<String, CacheItem> cache = new HashMap<>();
    private File rootDir;
    private File singleFile;

    public PoliciesCache() throws ParserConfigurationException {
        this(null);
    }
    public PoliciesCache(File rootDir) throws ParserConfigurationException {
        this.rootDir = rootDir;
    }
    public PoliciesCache(File singleFile, boolean single) throws ParserConfigurationException {
        this.singleFile = singleFile;
    }

    private static class CacheItem{
        PolicyCollection policyCollection;
        Long cacheTime;
        Long modTime;

        private CacheItem(PolicyCollection policyCollection, Long modTime) {
            this.policyCollection = policyCollection;
            this.modTime = modTime;
            this.cacheTime=System.currentTimeMillis();
        }

        public void touch(Long time) {
            this.cacheTime = time;
        }
    }

    long lastDirListCheckTime=0;
    private File[] lastDirList;
    private File[] listDirFiles() {
        if(System.currentTimeMillis()-lastDirListCheckTime > DIR_LIST_CHECK_DELAY) {
            doListDir();
        }
        return lastDirList;
    }

    private void doListDir() {
        lastDirList = null != rootDir
                      ? rootDir.listFiles(filenameFilter)
                      : singleFile != null ? new File[]{singleFile} : new File[0];
        lastDirListCheckTime = System.currentTimeMillis();
    }

    /**
     * @param file file
     * @throws PoliciesParseException
     * @deprecated use {@link #addSource(CacheableYamlSource)}
     */
    public synchronized void add(final File file) throws PoliciesParseException {
        getDocument(file);
    }
    public synchronized void addSource(final CacheableYamlSource source) throws PoliciesParseException {
        getDocument(source);
    }

    private PolicyCollection createEntry(final YamlSource source) throws PoliciesParseException {
        try {
            return YamlProvider.policiesFromSource(source);
        } catch (ParserException e1) {
            throw new PoliciesParseException("YAML syntax error: " + e1.toString(), e1);
        }catch (Exception e1) {
            throw new PoliciesParseException(e1);
        }
    }

    /**
     * @param file file
     * @return collection
     * @throws PoliciesParseException
     *
     * @deprecated use {@link #getDocument(CacheableYamlSource)}
     */
    public synchronized PolicyCollection getDocument(final File file) throws PoliciesParseException {
        return getDocument(YamlProvider.sourceFromFile(file));
    }
    public synchronized PolicyCollection getDocument(final CacheableYamlSource source) throws PoliciesParseException {
//        cacheTotal++;
        CacheItem entry = cache.get(source.getIdentity());

        long checkTime = System.currentTimeMillis();
        if (null == entry || ((checkTime - entry.cacheTime) > FILE_CHECK_DELAY)) {
            final long lastmod = source.getLastModified().getTime();
            if (null == entry || lastmod > entry.modTime) {
                    if (!source.isValid()) {
                        CacheItem remove = cache.remove(source.getIdentity());
                        entry = null;
//                        cacheRemove++;
                    } else {
//                        cacheMiss++;
                        PolicyCollection entry1 = createEntry(source);
                        if (null != entry1) {
                            entry = new CacheItem(entry1, lastmod);
                            cache.put(source.getIdentity(), entry);
                        } else {
                            cache.remove(source.getIdentity());
                            entry = null;
                        }
                    }
            }else{
//                cacheUnmodifiedHit++;
                entry.touch(checkTime);
            }
        }else{
//            cacheHit++;
        }
        return null != entry ? entry.policyCollection : null;
    }

    public Iterator<PolicyCollection> iterator() {
        final File[] files = listDirFiles();
        return new cacheIterator(asSources(files));
    }

    private Iterator<CacheableYamlSource> asSources(final File[] files) {
        ArrayList<CacheableYamlSource> list = new ArrayList<>();
        if(null!=files) {
            for (File file : files) {
                list.add(YamlProvider.sourceFromFile(file));
            }
        }
        return list.iterator();
    }

    private Map<CacheableYamlSource, Long> cooldownset = Collections.synchronizedMap(new HashMap<CacheableYamlSource, Long>());
    /**
     * Iterator over the PoliciesDocuments for the cache's files.  It skips
     * files that cannot be loaded.
     */
    private class cacheIterator implements Iterator<PolicyCollection> {
        Iterator<CacheableYamlSource> intIter;
        private CacheableYamlSource nextFile;
        private PolicyCollection nextDocument;

        public cacheIterator(final Iterator<CacheableYamlSource> intIter) {
            this.intIter = intIter;
            nextFile = this.intIter.hasNext() ? this.intIter.next() : null;
            loadNextDocument();
        }

        private void loadNextDocument() {
            while (hasNextFile() && null == nextDocument) {
                CacheableYamlSource nextFile2 = getNextFile();
                Long aLong = cooldownset.get(nextFile2);
                if (null != aLong && nextFile2.getLastModified().getTime() == aLong) {
                    logger.debug("Skip parsing of: " + nextFile2 + ". Reason: parse error cooldown until modified");
                    continue;
                } else if (null != aLong) {
                    //clear
                    cooldownset.remove(nextFile2);
                }
                try {
                    nextDocument = getDocument(nextFile2);
                } catch (PoliciesParseException e) {
                    logger.error("ERROR unable to parse aclpolicy: " + nextFile2 + ". Reason: " + e.getMessage());
                    logger.debug("ERROR unable to parse aclpolicy: " + nextFile2 + ". Reason: " + e.getMessage(), e);
                    cache.remove(nextFile2.getIdentity());
                    cooldownset.put(nextFile2, nextFile2.getLastModified().getTime());
                }
            }
        }

        private CacheableYamlSource getNextFile() {
            CacheableYamlSource next = nextFile;
            nextFile = intIter.hasNext() ? intIter.next() : null;
            return next;
        }

        private PolicyCollection getNextDocument() {
            PolicyCollection doc = nextDocument;
            nextDocument=null;
            loadNextDocument();
            return doc;
        }

        public boolean hasNextFile() {
            return null != nextFile;
        }

        public boolean hasNext() {
            return null != nextDocument;
        }

        public PolicyCollection next() {
            return getNextDocument();
        }

        public void remove() {
        }
    }

}
