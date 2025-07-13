package com.crazecoder.openfile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.PermissionChecker;

import com.crazecoder.openfile.utils.JsonUtil;
import com.crazecoder.openfile.utils.MapUtil;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * OpenFilePlugin
 */
public class OpenFilePlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {

    private static final int REQUEST_CODE = 33432;
    private static final int RESULT_CODE = 0x12;
    private static final String TYPE_STRING_APK = "application/vnd.android.package-archive";

    private @Nullable
    FlutterPluginBinding flutterPluginBinding;
    private @Nullable
    ActivityPluginBinding activityPluginBinding;

    private Context context;
    private Activity activity;
    private MethodChannel channel;

    private Result result;
    private String filePath;
    private String typeString;

    private boolean isResultSubmitted = false;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "open_file");
        channel.setMethodCallHandler(this);
        context = flutterPluginBinding.getApplicationContext();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        this.flutterPluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        this.activityPluginBinding = activityPluginBinding;
        activity = activityPluginBinding.getActivity();
        activityPluginBinding.addRequestPermissionsResultListener(this::onRequestPermissionsResult);
        activityPluginBinding.addActivityResultListener(this::onActivityResult);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding activityPluginBinding) {
        onAttachedToActivity(activityPluginBinding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this::onRequestPermissionsResult);
            activityPluginBinding.removeActivityResultListener(this::onActivityResult);
            activityPluginBinding = null;
        }
        activity = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        isResultSubmitted = false;
        if (call.method.equals("open_file")) {
            this.result = result;
            filePath = call.argument("file_path");
            if (call.hasArgument("type") && call.argument("type") != null) {
                typeString = call.argument("type");
            } else {
                typeString = getFileType(filePath);
            }
            if (pathRequiresPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!isFileAvailable()) {
                        return;
                    }
                    if (!isMediaStorePath() && !Environment.isExternalStorageManager()) {
                        result(-3, "Permission denied: android.Manifest.permission.MANAGE_EXTERNAL_STORAGE");
                        return;
                    }
                }
                if (hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    if (TYPE_STRING_APK.equals(typeString)) {
                        openApkFile();
                        return;
                    }
                    startActivity();
                } else {
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE);
                }
            } else {
                startActivity();
            }
        } else {
            result.notImplemented();
            isResultSubmitted = true;
        }
    }

    private boolean isMediaStorePath() {
        String[] mediaStorePath = {"/DCIM/", "/Pictures/", "/Movies/", "/Alarms/", "/Audiobooks/", "/Music/", "/Notifications/", "/Podcasts/", "/Ringtones/", "/Download/"};
        for (String s : mediaStorePath) {
            if (filePath.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean pathRequiresPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            String appDirCanonicalPath = new File(context.getApplicationInfo().dataDir).getCanonicalPath();
            String fileCanonicalPath = new File(filePath).getCanonicalPath();
            return !fileCanonicalPath.startsWith(appDirCanonicalPath);
        } catch (IOException e) {
            e.printStackTrace();
            return true;
        }
    }

    private boolean isFileAvailable() {
        if (filePath == null) {
            result(-4, "the file path cannot be null");
            return false;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            result(-2, "the " + filePath + " file does not exists");
            return false;
        }
        return true;
    }

    private void startActivity() {
        if (!isFileAvailable()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (TYPE_STRING_APK.equals(typeString)) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String packageName = context.getPackageName();
            Uri uri = FileProvider.getUriForFile(context, packageName + ".fileProvider.com.crazecoder.openfile", new File(filePath));
            intent.setDataAndType(uri, typeString);
        } else {
            intent.setDataAndType(Uri.fromFile(new File(filePath)), typeString);
        }
        int type = 0;
        String message = "done";
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            type = -1;
            message = "No APP found to open this file。";
        } catch (Exception e) {
            type = -4;
            message = "File opened incorrectly。";
        }
        result(type, message);
    }

    private String getFileType(String filePath) {
        // ... (rest of the getFileType method remains same)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void openApkFile() {
        if (!canInstallApk()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startInstallPermissionSettingActivity();
            } else {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.REQUEST_INSTALL_PACKAGES}, REQUEST_CODE);
            }
        } else {
            startActivity();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean canInstallApk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return activity.getPackageManager().canRequestPackageInstalls();
        }
        return hasPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startInstallPermissionSettingActivity() {
        if (activity == null) {
            return;
        }
        Uri packageURI = Uri.parse("package:" + activity.getPackageName());
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageURI);
        activity.startActivityForResult(intent, RESULT_CODE);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return false;
        if (hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE) && TYPE_STRING_APK.equals(typeString)) {
            openApkFile();
            return true;
        }
        for (String permission : permissions) {
            if (!hasPermission(permission)) {
                result(-3, "Permission denied: " + permission);
                return true;
            }
        }
        startActivity();
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RESULT_CODE) {
            if (canInstallApk()) {
                startActivity();
            } else {
                result(-3, "Permission denied: " + Manifest.permission.REQUEST_INSTALL_PACKAGES);
            }
            return true;
        }
        return false;
    }

    private void result(int type, String message) {
        if (result != null && !isResultSubmitted) {
            Map<String, Object> map = MapUtil.createMap(type, message);
            result.success(JsonUtil.toJson(map));
            isResultSubmitted = true;
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) == PermissionChecker.PERMISSION_GRANTED;
    }
}
