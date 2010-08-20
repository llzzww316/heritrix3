/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.archive.io;

import it.unimi.dsi.fastutil.io.FastBufferedOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import org.archive.util.ArchiveUtils;
import org.archive.util.FileUtils;
import org.archive.util.PropertyUtils;


/**
 * Member of {@link WriterPool}.
 * Implements rotating off files, file naming with some guarantee of
 * uniqueness, and position in file. Subclass to pick up functionality for a
 * particular Writer type.
 * @author stack
 * @version $Date$ $Revision$
 */
public abstract class WriterPoolMember implements ArchiveFileConstants {
    private final Logger logger = Logger.getLogger(this.getClass().getName());
    
    public static final String UTF8 = "UTF-8";
    
    /**
     * Default archival-aggregate filename template. 
     * 
     * Under usual assumptions -- hostnames aren't shared among crawling hosts; 
     * processes have unique PIDs and admin ports; timestamps inside one process
     * don't repeat (see UniqueTimestampService); clocks are generally 
     * accurate -- will generate a unique name.
     * 
     * Stands for Internet Archive Heritrix.
     */
    public static final String DEFAULT_TEMPLATE = 
        "${prefix}-${timestamp17}-${serialno}-${heritrix.pid}@${heritrix.hostname}#${heritrix.port}";
    
    /**
     * Default for file prefix.
     */
    public static final String DEFAULT_PREFIX = "WEB";

    /**
     * Reference to file we're currently writing.
     */
    private File f = null;

    /**
     *  Output stream for file.
     */
    private OutputStream out = null;
    
    /**
     * File output stream.
     * This is needed so can get at channel to find current position in file.
     */
    private FileOutputStream fos;
    
    private final boolean compressed;
    private List<File> writeDirs = null;
    private String template = DEFAULT_TEMPLATE;
    private String prefix = DEFAULT_PREFIX;
    private final long maxSize;
    private final String extension;

    /**
     * Creation date for the current file.
     * Set by {@link #createFile()}.
     */
	protected String currentTimestamp = "UNSET!!!";
    
	protected String currentBasename; 
	
    /**
     * A running sequence used making unique file names.
     */
    final private AtomicInteger serialNo;
    
    /**
     * Directories round-robin index.
     */
    private static int roundRobinIndex = 0;

    /**
     * NumberFormat instance for formatting serial number.
     *
     * Pads serial number with zeros.
     */
    private static NumberFormat serialNoFormatter = new DecimalFormat("00000");
    
    
    /**
     * Buffer to reuse writing streams.
     */
    private final byte [] scratchbuffer = new byte[4 * 1024];
 
    
    /**
     * Constructor.
     * Takes a stream. Use with caution. There is no upperbound check on size.
     * Will just keep writing.
     * 
     * @param serialNo  used to create unique filename sequences
     * @param out Where to write.
     * @param file File the <code>out</code> is connected to.
     * @param cmprs Compress the content written.
     * @param a14DigitDate If null, we'll write current time.
     * @throws IOException
     */
    protected WriterPoolMember(AtomicInteger serialNo, 
            final OutputStream out, final File file,
            final boolean cmprs, String a14DigitDate)
    throws IOException {
        this(serialNo, null, null, cmprs, -1, null);
        this.out = out;
        this.f = file;
    }
    
    /**
     * Constructor.
     *
     * @param serialNo  used to create unique filename sequences
     * @param dirs Where to drop files.
     * @param prefix File prefix to use.
     * @param cmprs Compress the records written. 
     * @param maxSize Maximum size for ARC files written.
     * @param extension Extension to give file.
     */
    public WriterPoolMember(AtomicInteger serialNo, 
            final List<File> dirs, final String prefix, 
            final boolean cmprs, final long maxSize, final String extension) {
        this(serialNo, dirs, prefix, "", cmprs, maxSize, extension);
    }
            
    /**
     * Constructor.
     *
     * @param serialNo  used to create unique filename sequences
     * @param dirs Where to drop files.
     * @param prefix File prefix to use.
     * @param cmprs Compress the records written. 
     * @param maxSize Maximum size for ARC files written.
     * @param suffix File tail to use.  If null, unused.
     * @param extension Extension to give file.
     */
    public WriterPoolMember(AtomicInteger serialNo,
            final List<File> dirs, final String prefix, 
            final String template, final boolean cmprs,
            final long maxSize, final String extension) {
        this.template = template;
        this.prefix = prefix;
        this.maxSize = maxSize;
        this.writeDirs = dirs;
        this.compressed = cmprs;
        this.extension = extension;
        this.serialNo = serialNo;
    }

	/**
	 * Call this method just before/after any significant write.
	 *
	 * Call at the end of the writing of a record or just before we start
	 * writing a new record.  Will close current file and open a new file
	 * if file size has passed out maxSize.
	 * 
	 * <p>Creates and opens a file if none already open.  One use of this method
	 * then is after construction, call this method to add the metadata, then
	 * call {@link #getPosition()} to find offset of first record.
	 *
	 * @exception IOException
	 */
    public void checkSize() throws IOException {
        if (this.out == null ||
                (this.maxSize != -1 && (this.f.length() > this.maxSize))) {
            createFile();
        }
    }

    /**
     * Create a new file.
     * Rotates off the current Writer and creates a new in its place
     * to take subsequent writes.  Usually called from {@link #checkSize()}.
     * @return Name of file created.
     * @throws IOException
     */
    protected String createFile() throws IOException {
        generateNewBasename();
        String name = currentBasename + '.' + this.extension  +
            ((this.compressed)? DOT_COMPRESSED_FILE_EXTENSION: "") +
            OCCUPIED_SUFFIX;
        File dir = getNextDirectory(this.writeDirs);
        return createFile(new File(dir, name));
    }
    
    protected String createFile(final File file) throws IOException {
    	close();
        this.f = file;
        this.fos = new FileOutputStream(this.f);
        this.out = new FastBufferedOutputStream(this.fos);
        logger.fine("Opened " + this.f.getAbsolutePath());
        return this.f.getName();
    }
    
    /**
     * @param dirs List of File objects that point at directories.
     * @return Find next directory to write an arc too.  If more
     * than one, it tries to round-robin through each in turn.
     * @throws IOException
     */
    protected File getNextDirectory(List<File> dirs)
    throws IOException {
        if (WriterPoolMember.roundRobinIndex >= dirs.size()) {
            WriterPoolMember.roundRobinIndex = 0;
        }
        File d = null;
        try {
            d = checkWriteable((File)dirs.
                get(WriterPoolMember.roundRobinIndex));
        } catch (IndexOutOfBoundsException e) {
            // Dirs list might be altered underneath us.
            // If so, we get this exception -- just keep on going.
        }
        if (d == null && dirs.size() > 1) {
            for (Iterator<File> i = dirs.iterator(); d == null && i.hasNext();) {
                d = checkWriteable((File)i.next());
            }
        } else {
            WriterPoolMember.roundRobinIndex++;
        }
        if (d == null) {
            throw new IOException("Directories unusable.");
        }
        return d;
    }
        
    protected File checkWriteable(File d) {
        if (d == null) {
            return d;
        }
        
        try {
            FileUtils.ensureWriteableDirectory(d);
        } catch(IOException e) {
            logger.warning("Directory " + d.getPath() + " is not" +
                " writeable or cannot be created: " + e.getMessage());
            d = null;
        }
        return d;
    }
 
    /**
     * Generate a new basename by interpolating values in the configured
     * template. Values come from local state, other configured values, and 
     * global system properties. The recommended default template will 
     * generate a unique basename under reasonable assumptions. 
     */
    protected void generateNewBasename() {
        Properties localProps = new Properties(); 
        localProps.setProperty("prefix", prefix);
        synchronized(this.getClass()) {
            // ensure that serialNo and timestamp are minted together (never inverted sort order)
            String paddedSerialNumber = WriterPoolMember.serialNoFormatter.format(serialNo.getAndIncrement());
            String timestamp17 = ArchiveUtils.getUnique17DigitDate(); 
            String timestamp14 = ArchiveUtils.getUnique14DigitDate(); 
            currentTimestamp = timestamp17;
            localProps.setProperty("serialno", paddedSerialNumber);
            localProps.setProperty("timestamp17", timestamp17);
            localProps.setProperty("timestamp14", timestamp14);
        }
        currentBasename = PropertyUtils.interpolateWithProperties(template, 
                localProps, System.getProperties());
    }


    /**
     * Get the file name
     * 
     * @return the filename, as if uncompressed
     */
    protected String getBaseFilename() {
        String name = this.f.getName();
        if (this.compressed && name.endsWith(DOT_COMPRESSED_FILE_EXTENSION)) {
            return name.substring(0,name.length() - 3);
        } else if(this.compressed &&
                name.endsWith(DOT_COMPRESSED_FILE_EXTENSION +
                    OCCUPIED_SUFFIX)) {
            return name.substring(0, name.length() -
                (3 + OCCUPIED_SUFFIX.length()));
        } else {
            return name;
        }
    }

	/**
	 * Get this file.
	 *
	 * Used by junit test to test for creation and when {@link WriterPool} wants
     * to invalidate a file.
	 *
	 * @return The current file.
	 */
    public File getFile() {
        return this.f;
    }

    /**
     * Post write tasks.
     * 
     * Has side effects.  Will open new file if we're at the upperbound.
     * If we're writing compressed files, it will wrap output stream with a
     * GZIP writer with side effect that GZIP header is written out on the
     * stream.
     *
     * @exception IOException
     */
    protected void preWriteRecordTasks()
    throws IOException {
        checkSize();
        if (this.compressed) {
            // Wrap stream in GZIP Writer.
            // The below construction immediately writes the GZIP 'default'
            // header out on the underlying stream.
            this.out = new CompressedStream(this.out);
        }
    }

    /**
     * Post file write tasks.
     * If compressed, finishes up compression and flushes stream so any
     * subsequent checks get good reading.
     *
     * @exception IOException
     */
    protected void postWriteRecordTasks()
    throws IOException {
        if (this.compressed) {
            CompressedStream o = (CompressedStream)this.out;
            o.finish();
            o.flush();
            o.end();
            this.out = o.getWrappedStream();
        }
    }
    
	/**
     * Position in current physical file.
     * Used making accounting of bytes written.
	 * @return Position in underlying file.  Call before or after writing
     * records *only* to be safe.
	 * @throws IOException
	 */
    public long getPosition() throws IOException {
        long position = 0;
        if (this.out != null) {
            this.out.flush();
        }
        if (this.fos != null) {
            // Call flush on underlying file though probably not needed assuming
            // above this.out.flush called through to this.fos.
            this.fos.flush();
            position = this.fos.getChannel().position();
        }
        return position;
    }

    public boolean isCompressed() {
        return compressed;
    }
    
    protected void write(final byte [] b) throws IOException {
    	this.out.write(b);
    }
    
	protected void flush() throws IOException {
		this.out.flush();
	}

	protected void write(byte[] b, int off, int len) throws IOException {
		this.out.write(b, off, len);
	}

	protected void write(int b) throws IOException {
		this.out.write(b);
	}

    /**
     * Copy bytes from the provided InputStream to the target file/stream being
     * written.
     * 
     * @param is
     *            InputStream to copy bytes from
     * @param recordLength
     *            expected number of bytes to copy
     * @param enforceLength
     *            whether to throw an exception if too many/too few bytes are
     *            available from stream
     * @throws IOException
     */
    protected void copyFrom(final InputStream is, final long recordLength,
            boolean enforceLength) throws IOException {
        int read = scratchbuffer.length;
        long tot = 0;
        while ((tot < recordLength)
                && (read = is.read(scratchbuffer)) != -1) {
            int write = read; 
            // never write more than enforced length
            write = (int) Math.min(write, recordLength - tot);
            tot += read;
            write(scratchbuffer, 0, write);
        }
        if (enforceLength && tot != recordLength) {
            // throw exception if desired for read vs. declared mismatches
            throw new IOException("Read " + tot + " but expected "
                    + recordLength);
        }
    }

    public void close() throws IOException {
        if (this.out == null) {
            return;
        }
        this.out.close();
        this.out = null;
        this.fos = null;
        if (this.f != null && this.f.exists()) {
            String path = this.f.getAbsolutePath();
            if (path.endsWith(OCCUPIED_SUFFIX)) {
                File f = new File(path.substring(0,
                        path.length() - OCCUPIED_SUFFIX.length()));
                if (!this.f.renameTo(f)) {
                    logger.warning("Failed rename of " + path);
                }
                this.f = f;
            }
            
            logger.fine("Closed " + this.f.getAbsolutePath() +
                    ", size " + this.f.length());
        }
    }
    
    protected OutputStream getOutputStream() {
    	return this.out;
    }

    /**
     * An override so we get access to underlying output stream.
     * and offer an end() that does not accompany closing underlying
     * stream.
     * @author stack
     */
    private class CompressedStream extends GZIPOutputStream {
        public CompressedStream(OutputStream out)
        throws IOException {
            super(out);
        }
        
        /**
         * @return Reference to stream being compressed.
         */
        OutputStream getWrappedStream() {
            return this.out;
        }
        
        /**
         * Release the deflater's native process resources,
         * which otherwise would not occur until either
         * finalization or DeflaterOutputStream.close() 
         * (which would also close underlying stream). 
         */
        public void end() {
            def.end();
        }
    }
}
