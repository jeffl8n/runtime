// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.

package net.dot;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.app.Activity;
import android.os.Environment;
import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public class MonoRunner extends Instrumentation
{
    static {
        // loadLibrary triggers JNI_OnLoad in these libs
        System.loadLibrary("%JNI_LIBRARY_NAME%");
        System.loadLibrary("monodroid");
    }

    static String testResultsDir;
    static String entryPointLibName = "%EntryPointLibName%";
    static Bundle result = new Bundle();

    private String[] argsToForward;

    @Override
    public void onCreate(Bundle arguments) {
        if (arguments != null) {
            ArrayList<String> argsList = new ArrayList<String>();
            for (String key : arguments.keySet()) {
                if (key.startsWith("env:")) {
                    String envName = key.substring("env:".length());
                    String envValue = arguments.getString(key);
                    setEnv(envName, envValue);
                    Log.i("DOTNET", "env:" + envName + "=" + envValue);
                } else if (key.equals("entrypoint:libname")) {
                    entryPointLibName = arguments.getString(key);
                } else {
                    String val = arguments.getString(key);
                    if (val != null) {
                        argsList.add(key);
                        argsList.add(val);
                    }
                }
            }

            argsToForward = argsList.toArray(new String[argsList.size()]);
        }

%EnvVariables%

        super.onCreate(arguments);
        start();
    }

    public static void initializeRuntime(String entryPointLibName, Context context) {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String cacheDir = context.getCacheDir().getAbsolutePath();

        File docsPath = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);

        // on Android API 30 there are "adb pull" permission issues with getExternalFilesDir() paths on emulators, see https://github.com/dotnet/xharness/issues/385
        if (docsPath == null || android.os.Build.VERSION.SDK_INT == 30) {
            testResultsDir = context.getCacheDir().getAbsolutePath();
        } else {
            testResultsDir = docsPath.getAbsolutePath();
        }

        // unzip libs and test files to filesDir
        unzipAssets(context, filesDir, "assets.zip");

        // set environment variables
        setEnv("HOME", filesDir);
        setEnv("TMPDIR", cacheDir);
        setEnv("TEST_RESULTS_DIR", testResultsDir);

        Log.i("DOTNET", "MonoRunner initializeRuntime, entryPointLibName=" + entryPointLibName);
        int localDateTimeOffset = getLocalDateTimeOffset();
        int rv = initRuntime(filesDir, entryPointLibName, localDateTimeOffset);
        if (rv != 0) {
            Log.e("DOTNET", "Failed to initialize runtime, return-code=" + rv);
            freeNativeResources();
            System.exit(rv);
        }
    }

    public static int executeEntryPoint(String entryPointLibName, String[] args) {
        int rv = execEntryPoint(entryPointLibName, args);
        return rv;
    }

    @Override
    public void onStart() {
        Looper.prepare();

        if (entryPointLibName == "") {
            Log.e("DOTNET", "Missing entrypoint argument, pass '-e entrypoint:libname <name.dll>' to adb to specify which program to run.");
            finish(1, null);
            return;
        }

        initializeRuntime(entryPointLibName, getContext());
        int retcode = executeEntryPoint(entryPointLibName, argsToForward);

        Log.i("DOTNET", "MonoRunner finished, return-code=" + retcode);
        result.putInt("return-code", retcode);

        // Xharness cli expects "test-results-path" with test results
        File testResults = new File(testResultsDir + "/testResults.xml");
        if (testResults.exists()) {
            Log.i("DOTNET", "MonoRunner finished, test-results-path=" + testResults.getAbsolutePath());
            result.putString("test-results-path", testResults.getAbsolutePath());
        }

        finish(retcode, result);
    }

    @Override
    public void onDestroy() {
        Log.i("DOTNET", "MonoRunner onDestroy");
        super.onDestroy();
        // Cleanup native resources
        freeNativeResources();
    }

    static void unzipAssets(Context context, String toPath, String zipName) {
        AssetManager assetManager = context.getAssets();
        try (InputStream inputStream = assetManager.open(zipName);
            ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(inputStream))) {

            ZipEntry zipEntry;
            byte[] buffer = new byte[4096];
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                String fileOrDirectory = zipEntry.getName();
                File file = new File(toPath, fileOrDirectory);
                File parent = new File(file.getParent());

                if (zipEntry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                else if (!parent.exists()) {
                    parent.mkdirs();
                }

                String fullToPath = file.getAbsolutePath();
                Log.i("DOTNET", "Extracting asset to " + fullToPath);

                int count = 0;
                FileOutputStream fileOutputStream = new FileOutputStream(fullToPath);
                while ((count = zipInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, count);
                }
                zipInputStream.closeEntry();
            }
        } catch (IOException e) {
            Log.e("DOTNET", e.getLocalizedMessage());
        }
    }

    static int getLocalDateTimeOffset() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return OffsetDateTime.now().getOffset().getTotalSeconds();
        } else {
            int offsetInMillis = Calendar.getInstance().getTimeZone().getRawOffset();
            return offsetInMillis / 1000;
        }
    }

    static native int setEnv(String key, String value);

    static native int initRuntime(String libsDir, String entryPointLibName, int local_date_time_offset);

    static native int execEntryPoint(String entryPointLibName, String[] args);

    static native void freeNativeResources();
}
