/* ownCloud Android Library is available under MIT license
 *   Copyright (C) 2016 ownCloud GmbH.
 *   
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *   
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN 
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */

package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.http.HttpConstants;
import com.owncloud.android.lib.common.http.methods.nonwebdav.GetMethod;
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener;
import com.owncloud.android.lib.common.network.WebdavUtils;
import com.owncloud.android.lib.common.operations.OperationCancelledException;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;

/**
 * Remote operation performing the download of a remote file in the ownCloud server.
 *
 * @author David A. Velasco
 * @author masensio
 */

public class DownloadRemoteFileOperation extends RemoteOperation {

    private static final String TAG = DownloadRemoteFileOperation.class.getSimpleName();
    private static final int FORBIDDEN_ERROR = 403;
    private static final int SERVICE_UNAVAILABLE_ERROR = 503;

    private Set<OnDatatransferProgressListener> mDataTransferListeners = new HashSet<>();
    private final AtomicBoolean mCancellationRequested = new AtomicBoolean(false);
    private long mModificationTimestamp = 0;
    private String mEtag = "";
    private GetMethod mGet;

    private String mRemotePath;
    private String mLocalFolderPath;

    public DownloadRemoteFileOperation(String remotePath, String localFolderPath) {
        mRemotePath = remotePath;
        mLocalFolderPath = localFolderPath;
    }

    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        RemoteOperationResult result;

        /// download will be performed to a temporal file, then moved to the final location
        File tmpFile = new File(getTmpPath());

        /// perform the download
        try {
            tmpFile.getParentFile().mkdirs();
            result = downloadFile(client, tmpFile);
            Log_OC.i(TAG, "Download of " + mRemotePath + " to " + getTmpPath() + ": " +
                result.getLogMessage());

        } catch (Exception e) {
            result = new RemoteOperationResult(e);
            Log_OC.e(TAG, "Download of " + mRemotePath + " to " + getTmpPath() + ": " +
                result.getLogMessage(), e);
        }

        return result;
    }


    private RemoteOperationResult downloadFile(OwnCloudClient client, File targetFile) throws
            Exception {

        RemoteOperationResult result;
        int status;
        boolean savedFile = false;
        mGet = new GetMethod(HttpUrl.parse(client.getWebdavUri() + WebdavUtils.encodePath(mRemotePath)));
        Iterator<OnDatatransferProgressListener> it;

        FileOutputStream fos = null;
        BufferedInputStream bis = null;
        try {
            status = client.executeHttpMethod(mGet);
            if (isSuccess(status)) {
                targetFile.createNewFile();
                bis = new BufferedInputStream(mGet.getResponseAsStream());
                fos = new FileOutputStream(targetFile);
                long transferred = 0;

                String contentLength = mGet.getResponseHeader("Content-Length");
                long totalToTransfer =
                        (contentLength != null
                                && contentLength.length() > 0)
                        ? Long.parseLong(contentLength)
                        : 0;

                byte[] bytes = new byte[4096];
                int readResult;
                while ((readResult = bis.read(bytes)) != -1) {
                    synchronized (mCancellationRequested) {
                        if (mCancellationRequested.get()) {
                            mGet.abort();
                            throw new OperationCancelledException();
                        }
                    }
                    fos.write(bytes, 0, readResult);
                    transferred += readResult;
                    synchronized (mDataTransferListeners) {
                        it = mDataTransferListeners.iterator();
                        while (it.hasNext()) {
                            it.next().onTransferProgress(readResult, transferred, totalToTransfer,
                                targetFile.getName());
                        }
                    }
                }
                if (transferred == totalToTransfer) {  // Check if the file is completed
                    savedFile = true;
                    final String modificationTime =
                            mGet.getResponseHeaders().get("Last-Modified") != null
                            ? mGet.getResponseHeaders().get("Last-Modified")
                            :  mGet.getResponseHeader("last-modified");

                    if (modificationTime != null) {
                        final Date d = WebdavUtils.parseResponseDate(modificationTime);
                        mModificationTimestamp = (d != null) ? d.getTime() : 0;
                    } else {
                        Log_OC.e(TAG, "Could not read modification time from response downloading " + mRemotePath);
                    }

                    // TODO mEtag = WebdavUtils.getEtagFromResponse(mGet);
                    if (mEtag.length() == 0) {
                        Log_OC.e(TAG, "Could not read eTag from response downloading " + mRemotePath);
                    }

                } else {
                    client.exhaustResponse(mGet.getResponseAsStream());
                    // TODO some kind of error control!
                }

            } else if (status != FORBIDDEN_ERROR && status != SERVICE_UNAVAILABLE_ERROR) {
                client.exhaustResponse(mGet.getResponseAsStream());

            } // else, body read by RemoteOperationResult constructor

            result = isSuccess(status)
                    ? new RemoteOperationResult(RemoteOperationResult.ResultCode.OK)
                    : new RemoteOperationResult(mGet);
        } finally {
            if (fos != null) fos.close();
            if (bis != null) bis.close();
            if (!savedFile && targetFile.exists()) {
                targetFile.delete();
            }
        }
        return result;
    }

    private boolean isSuccess(int status) {
        return (status == HttpConstants.HTTP_OK);
    }

    private String getTmpPath() {
        return mLocalFolderPath + mRemotePath;
    }

    public void addDatatransferProgressListener(OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.add(listener);
        }
    }

    public void removeDatatransferProgressListener(OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.remove(listener);
        }
    }

    public void cancel() {
        mCancellationRequested.set(true);   // atomic set; there is no need of synchronizing it
    }

    public long getModificationTimestamp() {
        return mModificationTimestamp;
    }

    public String getEtag() {
        return mEtag;
    }
}