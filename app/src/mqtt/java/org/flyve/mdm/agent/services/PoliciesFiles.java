/*
 * Copyright Teclib. All rights reserved.
 *
 * Flyve MDM is a mobile device management software.
 *
 * Flyve MDM is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * Flyve MDM is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * ------------------------------------------------------------------------------
 * @author    Rafael Hernandez
 * @copyright Copyright Teclib. All rights reserved.
 * @license   GPLv3 https://www.gnu.org/licenses/gpl-3.0.html
 * @link      https://github.com/flyve-mdm/android-mdm-agent
 * @link      https://flyve-mdm.com
 * ------------------------------------------------------------------------------
 */

package org.flyve.mdm.agent.services;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.PowerManager;

import org.flyve.mdm.agent.R;
import org.flyve.mdm.agent.core.Routes;
import org.flyve.mdm.agent.data.database.MqttData;
import org.flyve.mdm.agent.data.database.setup.AppDataBase;
import org.flyve.mdm.agent.ui.MDMAgent;
import org.flyve.mdm.agent.utils.AppThreadManager;
import org.flyve.mdm.agent.utils.ConnectionHTTP;
import org.flyve.mdm.agent.utils.FlyveLog;
import org.flyve.mdm.agent.utils.Helpers;
import org.flyve.mdm.agent.utils.StorageFolder;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;

import static org.flyve.mdm.agent.ui.MDMAgent.getMqttClient;

public class PoliciesFiles extends AsyncTask<String, Integer, Integer> {

    private Context context;
    private Routes routes;
    private MqttData cache;
    private String taskId;
    private String type;
    private String deployFile;
    private String url;


    /**
     * This constructor loads the context of the current class
     * @param context of the class
     */
    public PoliciesFiles(Context context) {
        this.context = context;
        routes = new Routes(context);
        cache =  new MqttData(context);
    }

    /**
     * Thread to download files or package on background
     * @param args args[0] = "file|package",
     *             args[1] = "file path (/storage/sdcard/documents)"
     *             args[2] = "file id on the server"
     *             args[3] = "valid session token"
     *             args[4] = "task id"
     * @return
     */
    @Override
    protected Integer doInBackground(String... args) {

        // check values
        if(args[0].isEmpty() ||
            args[1].isEmpty() ||
            args[2].isEmpty()  ||
                args[3].isEmpty()||
                args[4].isEmpty()) {
            AppThreadManager manager = MDMAgent.getAppThreadManager();
            manager.finishProcess(context);
            return 0;
        }

        this.type = args[0];
        this.taskId = args[4];
        this.deployFile = args[1];
        this.url = routes.PluginFlyvemdmTaskstatus(taskId);

        if(args[0].equals("file")) {

            if(downloadFile(args[1], args[2], args[3])) {
                return 1;
            }

        } else if (args[0].equals("package")) {
            if(downloadApk(args[1], args[2], args[3], args[4])) {
                return 1;
            }
        }

        return 0;
    }

    protected void onPostExecute(Integer result) {
        super.onPostExecute(result);
    }

    /**
     * Download and save file from Id to path
     * @param path String path to save the file on device
     * @param id String Id from
     * @param sessionToken
     */
    public Boolean downloadFile(String path, String id, String sessionToken) {

        //prevent CPU from going off if the user presses the power button during download
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        wl.acquire();

        String filePath = "";
        try {
            filePath = new StorageFolder(context).convertPath(path);
        } catch (Exception ex) {
            FlyveLog.e(this.getClass().getName() + ", downloadFile", ex.getMessage());
        }

        final String url = routes.pluginFlyvemdmFile(id);
        String completeFilePath = download(url, filePath, sessionToken);

        return(completeFilePath.equalsIgnoreCase(""));
    }

    /**
     * Download, save and install app
     * @param appName String package of the app
     * @param id String Id from
     * @param sessionToken
     */
    public Boolean downloadApk(String appName, String id, String sessionToken, String taskId) {

        FlyveLog.d("Application name: " + appName);

        //prevent CPU from going off if the user presses the power button during download
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        wl.acquire();

        String filePath = "";
        try {
            filePath = new StorageFolder(context).getApkDir();
        } catch (Exception ex) {
            AppThreadManager manager = MDMAgent.getAppThreadManager();
            manager.finishProcess(context);
            FlyveLog.e(this.getClass().getName() + ", downloadApk", ex.getMessage());
        }

        final String url = routes.pluginFlyvemdmPackage(id);
        String completeFilePath = download(url, filePath, sessionToken);
        if(completeFilePath.equalsIgnoreCase("")) {
            AppThreadManager manager = MDMAgent.getAppThreadManager();
            manager.finishProcess(context);
            return false;
        } else {

            MDMAgent.setMqttClient(getMqttClient());
            AppThreadManager manager = MDMAgent.getAppThreadManager();
            manager.finishProcess(context);

            if(Helpers.isSystemApp(context).equalsIgnoreCase("1")) {
                // Silently for System apps
                Helpers.installApkSilently(completeFilePath);
            } else {
                // Regular app
                Helpers.installApk(context, id, completeFilePath, taskId);
            }

            return true;
        }
    }

    /**
     * Download file from url to start need a fresh sessionToken
     * @param url String url to download the file
     * @param path String path to save
     * @return String complete path with name of the file
     */
    private String download(final String url, final String path, String sessionToken) {
        HashMap<String, String> header = new HashMap();
        header.put("Session-Token", sessionToken);

        String data = ConnectionHTTP.getSyncWebData(url, "GET",header);
        if(data.contains("ERROR")) {
            Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.download_file_fail));
            AppThreadManager manager = MDMAgent.getAppThreadManager();
            manager.finishProcess(context);

            FlyveLog.e(this.getClass().getName() + ", download", data + "\n" + url);
        } else {
            try {
                JSONObject jsonObjDownload = new JSONObject(data);
                return getFile(jsonObjDownload, path, url, data, sessionToken);
            } catch (Exception ex) {
                FlyveLog.e(this.getClass().getName() + ", download", ex.getMessage() + "\n" + url);
                return "";
            }
        } // endif Exception
        return "";
    }

    private String getFile(JSONObject jsonObjDownload, String path, String url, String data, String sessionToken) {

        String fileName = "";

        try {
            // Both has name
            if (jsonObjDownload.has("name")) {
                fileName = jsonObjDownload.getString("name");
            }

            // is APK
            if (jsonObjDownload.has("dl_filename")) {
                fileName = jsonObjDownload.getString("dl_filename");
            }

            // validating if folder exists or create
            new File(path).mkdirs();

            // validating if file exists
            String filePath = path + fileName;
            File file = new File(filePath);
            if (file.exists()) {
                FlyveLog.i("File exists: " + filePath);
                addFile(file, fileName);
                return file.getAbsolutePath();
            }

            Boolean isSave = ConnectionHTTP.getSyncFile(url, filePath , sessionToken, new ConnectionHTTP.ProgressCallback() {
                @Override
                public void progress(int value) {
                    publishProgress(value);
                }
            });

            if (isSave) {
                publishProgress(100);
                FlyveLog.i(context.getString(R.string.download_file_ready) + file.getAbsolutePath());

                addFile(file, fileName);

                return file.getAbsolutePath();
            } else {
                publishProgress(100);
                FlyveLog.e(this.getClass().getName() + ", getFile", "Download fail: " + data + "\n" + url);

                return "";
            }

        } catch(Exception ex) {
            FlyveLog.e(this.getClass().getName() + ", getFile", ex.getMessage()+ "\n" + url);
            return "";
        }
    }

    private void addFile(File file, String fileName) {
        org.flyve.mdm.agent.data.database.entity.File dataFile = new org.flyve.mdm.agent.data.database.entity.File();
        dataFile.fileName = fileName;
        dataFile.filePath = file.getAbsolutePath();

        AppDataBase dataBase = AppDataBase.getAppDatabase(context);
        dataBase.FileDao().deleteByName(fileName);
        dataBase.FileDao().insert(dataFile);
    }

    /**
     * Remove the file according to the given path
     * @param filePath
     * @return boolean true if file deleted, false otherwise
     */
    public boolean removeFile(String filePath) {
        try {
            String realPath = new StorageFolder(context).convertPath(filePath);
            File file = new File(realPath);
            return file.delete();
        } catch (Exception ex) {
            FlyveLog.e(this.getClass().getName() + ", removeFile", ex.getMessage() + "\n" + filePath);
            return false;
        }
    }

    /**
     * Uninstall the Android Package
     * @param mPackage to uninstall
     * @return int if it succeed 1, otherwise 0
     */
    public int removeApk(String mPackage){
        Uri packageUri = Uri.parse("package:"+mPackage);
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            FlyveLog.e(this.getClass().getName() + ", removeApk", e.getMessage() + "\n" + mPackage);
            return 0;
        }
        return 1;
    }
}
