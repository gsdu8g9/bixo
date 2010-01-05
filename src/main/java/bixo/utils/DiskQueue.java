package bixo.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.InvalidParameterException;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

// FUTURE KKr - worry about multi-threaded access. Could wrap syncronized(_memoryQueue)
// around most class bodies (also synchronized(_backingStore) for file methods.

/**
 * A queue that writes extra elements to disk, and reads them in as needed.
 * 
 * This implementation is optimized for being filled once (ie by the iterator in a reducer)
 * and then incrementally read. So it wouldn't work very well if reads/writes were happening
 * simultaneously, once anything had spilled to disk.
 *
 */
public class DiskQueue<E extends Serializable> extends AbstractQueue<E> {
    private static final Logger LOGGER = Logger.getLogger(DiskQueue.class);

    // The _memoryQueue represents the head of the queue. It can also be the tail, if
    // nothing has spilled over onto the disk.
    private LinkedBlockingQueue<E> _memoryQueue;
    
    // Number of elements in the backing store file on disk.
    private int _fileElements;

    private ObjectOutputStream _fileOut;
    private ObjectInputStream _fileIn;
    
    // When moving elements from disk to memory, we don't know whether the memory queue
    // has space until the offer is rejected. So rather than trying to push back an element
    // into the file, just cache it in _fileInSaved.
    private E _fileInSaved;
    private File _backingStore;

    // FUTURE - KKr could have another memory-resident queue for the tail, so that writes
    // to the file are buffered that way, and we get better performance if the queue is
    // being used for parallel read/write access, so you frequently wind up crossing the
    // 'spill' boundary from memory onto disk.
    //
    // Another issue with parallel read/write is that the backing store file could grow
    // unbounded, if elements spill to disk and then the disk is never exhausted when
    // refilling the memory queue, and you keep adding more elements at the same time.
    
    /**
     * Construct a disk-backed queue that keeps at most <maxSize> elements in memory.
     * 
     * @param maxSize Maximum number of elements to keep in memory.
     */
    public DiskQueue(int maxSize) {
        if (maxSize < 1) {
            throw new InvalidParameterException("DiskQueue max size must be at least one");
        }

        _memoryQueue = new LinkedBlockingQueue<E>(maxSize);
    }


    /* (non-Javadoc)
     * @see java.lang.Object#finalize()
     * 
     * Close down streams, and toss the temp file.
     */
    @Override
    protected void finalize() throws Throwable {
        closeFile();
    }


    /**
     * Make sure the file streams are all closed down, the temp file is closed, and the
     * temp file has been deleted.
     */
    private void closeFile() {
        if (_backingStore != null) {
            IoUtils.safeClose(_fileIn);
            _fileIn = null;
            _fileInSaved = null;

            IoUtils.safeClose(_fileOut);
            _fileOut = null;

            _fileElements = 0;
            
            _backingStore.delete();
            _backingStore = null;
        }
    }

    private void openFile() throws IOException {
        if (_backingStore == null) {
            _backingStore = File.createTempFile(DiskQueue.class.getSimpleName() + "-backingstore-", null);
            _fileOut = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(_backingStore)));
            
            // Flush output file, so there's something written when we open the input stream.
            _fileOut.flush();
            
            _fileIn = new ObjectInputStream(new FileInputStream(_backingStore));
        }
    }

    @Override
    public Iterator<E> iterator() {
        // FUTURE KKr - support iterator
        throw new RuntimeException("Iterator not supported for DiskQueue (yet)");
    }


    @Override
    public int size() {
        return _memoryQueue.size() + _fileElements + (_fileInSaved != null ? 1 : 0);
    }


    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null for AbstractQueue");
        }
        
        // If there's anything in the file, or the queue is full, then we have to write to the file.
        if ((_fileElements > 0) || !_memoryQueue.offer(element)) {
            try {
                openFile();
                _fileOut.writeObject(element);
            } catch (IOException e) {
                LOGGER.error("Error writing to DiskQueue backing store", e);
                return false;
            }

            _fileElements += 1;
        }

        return true;
    }

    @Override
    public E peek() {
        loadMemoryQueue();

        return _memoryQueue.peek();
    }

    @Override
    public E poll() {
        loadMemoryQueue();

        return _memoryQueue.poll();
    }

    /* (non-Javadoc)
     * @see java.util.AbstractQueue#clear()
     * 
     * Implement faster clear (so AbstractQueue doesn't call poll() repeatedly)
     */
    @Override
    public void clear() {
        _memoryQueue.clear();
        
        closeFile();
    }
    
    @SuppressWarnings("unchecked")
    private void loadMemoryQueue() {
        if (_memoryQueue.isEmpty()) {
            // See if we have one saved element from the previous read request
            if (_fileInSaved != null) {
                if (!_memoryQueue.offer(_fileInSaved)) {
                    throw new RuntimeException("Impossible error - can't offer to an empty queue");
                }

                _fileInSaved = null;
            }

            try {
                openFile();
                
                _fileOut.flush();

                while (_fileElements > 0) {
                    E nextFileElement = (E)_fileIn.readObject();
                    _fileElements -= 1;

                    if (!_memoryQueue.offer(nextFileElement)) {
                        _fileInSaved = nextFileElement;
                        return;
                    }
                }

                // Nothing left in the file, so close it.
                closeFile();

                // FUTURE KKr - file queue is empty, so could reset length of file, read/write offsets
                // to start from zero instead of closing file (but for current use case of fill once, drain
                // once this works just fine)
            } catch (IOException e) {
                LOGGER.error("Error reading from DiskQueue backing store", e);
                return;
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Impossible error - can't find class for object in backing store");
            }
        }
    }
}