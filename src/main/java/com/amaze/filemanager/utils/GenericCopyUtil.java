package com.amaze.filemanager.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.support.v4.provider.DocumentFile;
import android.util.Log;

import com.amaze.filemanager.filesystem.BaseFile;
import com.amaze.filemanager.filesystem.HFile;
import com.amaze.filemanager.filesystem.RootHelper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

/**
 * Created by vishal on 26/10/16.
 *
 * Base class to handle file copy.
 */

public class GenericCopyUtil {

    private BaseFile mSourceFile;
    private HFile mTargetFile;
    private Context mContext;   // context needed to find the DocumentFile in otg/sd card
    public static final String PATH_FILE_DESCRIPTOR = "/proc/self/fd/";

    public static final int DEFAULT_BUFFER_SIZE =  8192;

    public GenericCopyUtil(Context context) {
        this.mContext = context;
    }

    /**
     * Starts copy of file
     * Supports : {@link File}, {@link jcifs.smb.SmbFile}, {@link DocumentFile}
     * @throws IOException
     */
    private void startCopy() throws IOException {

        FileInputStream inputStream = null;
        FileOutputStream outputStream = null;
        FileChannel inChannel = null;
        FileChannel outChannel = null;
        BufferedInputStream bufferedInputStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        ParcelFileDescriptor inputFileDescriptor = null;
        ParcelFileDescriptor outputFileDescriptor = null;

        try {

            // initializing the input channels based on file types
            if (mSourceFile.isOtgFile()) {
                // source is in otg
                ContentResolver contentResolver = mContext.getContentResolver();
                DocumentFile documentSourceFile = RootHelper.getDocumentFile(mSourceFile.getPath(),
                        mContext, false);

                try {

                    // try getting file descriptor since we know it's a defined file
                    inputFileDescriptor = contentResolver
                            .openFileDescriptor(documentSourceFile.getUri(), "r");
                    File procFile = new File(PATH_FILE_DESCRIPTOR + inputFileDescriptor.getFd());
                    //if (!procFile.isFile()) throw new NullPointerException();

                    bufferedInputStream = new BufferedInputStream(new FileInputStream(procFile), DEFAULT_BUFFER_SIZE);
                } catch (FileNotFoundException e) {
                    // falling back to getting input stream from uri
                    bufferedInputStream = new BufferedInputStream(contentResolver
                            .openInputStream(documentSourceFile.getUri()), DEFAULT_BUFFER_SIZE);
                }
            } else if (mSourceFile.isSmb()) {

                // source is in smb
                bufferedInputStream = new BufferedInputStream(mSourceFile.getInputStream(), DEFAULT_BUFFER_SIZE);
            } else {

                // source file is neither smb nor otg; getting a channel from direct file instead of stream
                inChannel = new RandomAccessFile(new File(mSourceFile.getPath()), "r").getChannel();
            }

            // initializing the output channels based on file types
            if (mTargetFile.isOtgFile()) {

                // target in OTG, obtain streams from DocumentFile Uri's

                ContentResolver contentResolver = mContext.getContentResolver();
                DocumentFile documentTargetFile = RootHelper.getDocumentFile(mTargetFile.getPath(),
                        mContext, true);

                try {

                    // try getting file descriptor since we know it's a defined file
                    outputFileDescriptor = contentResolver
                            .openFileDescriptor(documentTargetFile.getUri(), "rw");
                    File procFile = new File(PATH_FILE_DESCRIPTOR + outputFileDescriptor);

                    bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(procFile), DEFAULT_BUFFER_SIZE);
                } catch (FileNotFoundException e) {
                    // falling back to getting input stream from uri
                    bufferedOutputStream = new BufferedOutputStream(contentResolver
                            .openOutputStream(documentTargetFile.getUri()), DEFAULT_BUFFER_SIZE);
                }
            } else if (mTargetFile.isSmb()) {

                bufferedOutputStream = new BufferedOutputStream(mTargetFile.getOutputStream(mContext), DEFAULT_BUFFER_SIZE);
            } else {
                // copying normal file, target not in OTG
                outChannel = new RandomAccessFile(new File(mTargetFile.getPath()), "rw").getChannel();
            }

            if (bufferedInputStream!=null) {
                if (bufferedOutputStream!=null) copyFile(bufferedInputStream, bufferedOutputStream);
                else if (outChannel!=null) {
                    copyFile(bufferedInputStream, outChannel);
                }
            } else if (inChannel!=null) {
                if (bufferedOutputStream!=null) copyFile(inChannel, bufferedOutputStream);
                else if (outChannel!=null)  copyFile(inChannel, outChannel);
            }

        } catch (IOException e) {
            e.printStackTrace();
            Log.d(getClass().getSimpleName(), "I/O Error!");
            throw new IOException();
        } finally {

            try {
                if (inChannel!=null) inChannel.close();
                if (outChannel!=null) outChannel.close();
                if (inputStream!=null) inputStream.close();
                if (outputStream!=null) outputStream.close();
                if (bufferedInputStream!=null) bufferedInputStream.close();
                if (bufferedOutputStream!=null) bufferedOutputStream.close();
                if (inputFileDescriptor!=null)  inputFileDescriptor.close();
                if (outputFileDescriptor!=null) outputFileDescriptor.close();
            } catch (IOException e) {
                e.printStackTrace();
                // failure in closing stream
            }
        }
    }

    /**
     * Method exposes this class to initiate copy
     * @param sourceFile the source file, which is to be copied
     * @param targetFile the target file
     */
    public void copy(BaseFile sourceFile, HFile targetFile) throws IOException{

        this.mSourceFile = sourceFile;
        this.mTargetFile = targetFile;

        startCopy();
    }

    private void copyFile(BufferedInputStream bufferedInputStream, FileChannel outChannel)
            throws IOException {

        MappedByteBuffer byteBuffer = outChannel.map(FileChannel.MapMode.READ_WRITE, 0,
                mSourceFile.getSize());
        int count;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        do {
            count = bufferedInputStream.read(buffer);
            if (count!=-1) {

                byteBuffer.put(buffer, 0, count);
                ServiceWatcherUtil.POSITION+=count;
            }
        } while (count!=-1);
    }

    private void copyFile(FileChannel inChannel, FileChannel outChannel) throws IOException {

        //MappedByteBuffer inByteBuffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());
        //MappedByteBuffer outByteBuffer = outChannel.map(FileChannel.MapMode.READ_WRITE, 0, inChannel.size());

        ReadableByteChannel inByteChannel = new CustomReadableByteChannel(inChannel);
        outChannel.transferFrom(inByteChannel, 0, Long.MAX_VALUE);
    }

    private void copyFile(BufferedInputStream bufferedInputStream, BufferedOutputStream bufferedOutputStream)
            throws IOException {
        int count = 0;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];

        while (count != -1) {

            count = bufferedInputStream.read(buffer);
            if (count!=-1) {

                bufferedOutputStream.write(buffer, 0 , count);
                ServiceWatcherUtil.POSITION+=count;
            }
        }
        bufferedOutputStream.flush();
    }

    private void copyFile(FileChannel inChannel, BufferedOutputStream bufferedOutputStream)
            throws IOException {
        MappedByteBuffer inBuffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, mSourceFile.getSize());

        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        while (inBuffer.hasRemaining()) {

            int count = 0;
            for (int i=0; i<buffer.length && inBuffer.hasRemaining(); i++) {
                buffer[i] = inBuffer.get();
                count++;
            }
            bufferedOutputStream.write(buffer, 0, count);
            ServiceWatcherUtil.POSITION = inBuffer.position();
        }
        bufferedOutputStream.flush();
    }

    /**
     * Inner class responsible for getting a {@link ReadableByteChannel} from the input channel
     * and to watch over the read progress
     */
    class CustomReadableByteChannel implements ReadableByteChannel {

        ReadableByteChannel byteChannel;

        CustomReadableByteChannel(ReadableByteChannel byteChannel) {
            this.byteChannel = byteChannel;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            int bytes;
            if (((bytes = byteChannel.read(dst))>0)) {

                ServiceWatcherUtil.POSITION += bytes;
                return bytes;

            }
            return 0;
        }

        @Override
        public boolean isOpen() {
            return byteChannel.isOpen();
        }

        @Override
        public void close() throws IOException {

            byteChannel.close();
        }
    }
}
