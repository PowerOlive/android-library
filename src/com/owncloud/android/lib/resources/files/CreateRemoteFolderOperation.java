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


import android.net.Uri;

import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.http.HttpConstants;
import com.owncloud.android.lib.common.http.methods.webdav.MkColMethod;
import com.owncloud.android.lib.common.network.WebdavUtils;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.status.OwnCloudVersion;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;


/**
 * Remote operation performing the creation of a new folder in the ownCloud server.
 *
 * @author David A. Velasco
 * @author masensio
 */
public class CreateRemoteFolderOperation extends RemoteOperation {

    private static final String TAG = CreateRemoteFolderOperation.class.getSimpleName();

    private static final int READ_TIMEOUT = 30000;
    private static final int CONNECTION_TIMEOUT = 5000;

    private String mRemotePath;
    private boolean mCreateFullPath;
    protected boolean createChunksFolder;

    /**
     * Constructor
     * @param remotePath     Full path to the new directory to create in the remote server.
     * @param createFullPath 'True' means that all the ancestor folders should be created.
     */
    public CreateRemoteFolderOperation(String remotePath, boolean createFullPath) {
        mRemotePath = remotePath;
        mCreateFullPath = createFullPath;
        createChunksFolder = false;
    }

    /**
     * Performs the operation
     *
     * @param client Client object to communicate with the remote ownCloud server.
     */
    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        RemoteOperationResult result;
        OwnCloudVersion version = client.getOwnCloudVersion();
        boolean versionWithForbiddenChars =
            (version != null && version.isVersionWithForbiddenCharacters());
        boolean noInvalidChars = FileUtils.isValidPath(mRemotePath, versionWithForbiddenChars);
        if (noInvalidChars) {
            result = createFolder(client);
            if (!result.isSuccess() && mCreateFullPath &&
                RemoteOperationResult.ResultCode.CONFLICT == result.getCode()) {
                result = createParentFolder(FileUtils.getParentPath(mRemotePath), client);
                if (result.isSuccess()) {
                    result = createFolder(client);    // second (and last) try
                }
            }

        } else {
            result = new RemoteOperationResult(ResultCode.INVALID_CHARACTER_IN_NAME);
        }

        return result;
    }


    private RemoteOperationResult createFolder(OwnCloudClient client) {
        RemoteOperationResult result;
        try {
            Uri webDavUri = createChunksFolder ? client.getNewUploadsWebDavUri() : client.getNewFilesWebDavUri();
            final MkColMethod mkcol = new MkColMethod(HttpUrl.parse(webDavUri + WebdavUtils.encodePath(mRemotePath)));
            mkcol.setReadTimeout(READ_TIMEOUT, TimeUnit.SECONDS);
            mkcol.setConnectionTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS);
            final int status = client.executeHttpMethod(mkcol);

            result = (status == HttpConstants.HTTP_CREATED)
                    ? new RemoteOperationResult(ResultCode.OK)
                    : new RemoteOperationResult(mkcol);
            Log_OC.d(TAG, "Create directory " + mRemotePath + ": " + result.getLogMessage());
            client.exhaustResponse(mkcol.getResponseAsStream());

        } catch (Exception e) {
            result = new RemoteOperationResult(e);
            Log_OC.e(TAG, "Create directory " + mRemotePath + ": " + result.getLogMessage(), e);
        }

        return result;
    }

    private RemoteOperationResult createParentFolder(String parentPath, OwnCloudClient client) {
        RemoteOperation operation = new CreateRemoteFolderOperation(parentPath, mCreateFullPath);
        return operation.execute(client);
    }
}