package org.schabi.newpipe;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Created by Adam Howard on 08/11/15.
 *
 * Copyright (c) Adam Howard <achdisposable1@gmail.com> 2015
 *
 * BackgroundPlayer.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

/**Plays the audio stream of videos in the background. */
public class BackgroundPlayer extends Service /*implements MediaPlayer.OnPreparedListener*/ {

    private static final String TAG = BackgroundPlayer.class.toString();
    //private Looper mServiceLooper;
    //private ServiceHandler mServiceHandler;

    public BackgroundPlayer() {
        super();
    }

    @Override
    public void onCreate() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        super.onCreate();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Playing in background", Toast.LENGTH_SHORT).show();//todo:translation string

        String source = intent.getDataString();
        String videoTitle = intent.getStringExtra("title");

        PlayerThread player = new PlayerThread(source, videoTitle, this);
        player.start();

        // If we get killed after returning here, don't restart
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding (yet?), so return null
        return null;
    }

    @Override
    public void onDestroy() {
        //Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show();
    }

    private class PlayerThread extends Thread {
        private MediaPlayer mediaPlayer;
        private String source;
        private String title;
        private BackgroundPlayer owner;
        public PlayerThread(String src, String title, BackgroundPlayer owner) {
            this.source = src;
            this.title = title;
            this.owner = owner;
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
        @Override
        public void run() {
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);//cpu lock
            try {
                mediaPlayer.setDataSource(source);
                mediaPlayer.prepare(); //We are already in a separate worker thread,
                //so calling the blocking prepare() method should be ok

                //alternatively:
                //mediaPlayer.setOnPreparedListener(this);
                //mediaPlayer.prepareAsync(); //prepare async to not block main thread
            } catch (IOException ioe) {
                ioe.printStackTrace();
                Log.e(TAG, "video source:" + source);
                Log.e(TAG, "video title:" + title);
                //can't do anything useful without a file to play; exit early
                return;
            }

            WifiManager wifiMgr = ((WifiManager)getSystemService(Context.WIFI_SERVICE));
            WifiManager.WifiLock wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);

            mediaPlayer.setOnCompletionListener(new EndListener(wifiLock));//listen for end of video

            //get audio focus
            /*
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);

            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // could not get audio focus.
            }*/
            wifiLock.acquire();
            mediaPlayer.start();
/*
        1. For each button in the notification, create an Intent pointing to the handling class
        2. From this, create a corresponding PendingIntent.
        3. Then, for each button, call NotificationBuilder.addAction().
            the exact method signature will depend on whether you just specify the icon,label etc (deprecated),
            or if you also have to create a Notification.Action (and Notification.Action.Builder).
            in any case, in the class referred to in the explicit intent,
        4. Write the method body of whatever callback android says to use for a service.
            Probably onStartCommand. But isn't that only called when startService() is? and
            we need to call this whenever a notification button is pressed! we don't want to restart
            the service every time the button is pressed! Oh I don't know yet....
            see: http://www.vogella.com/tutorials/AndroidNotifications/article.html
            and maybe also: http://stackoverflow.com/questions/10613524
*/
            //mediaPlayer.getCurrentPosition()
            int vidLength = mediaPlayer.getDuration();
    //todo: make it so that tapping the notification brings you back to the Video's DetailActivity
            NotificationCompat.Builder noteBuilder = new NotificationCompat.Builder(owner);
            noteBuilder
                    .setPriority(Notification.PRIORITY_LOW)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .setContentTitle(title)
                    .setContentText("NewPipe is playing in the background")//todo: translation string
                    .setOngoing(true)
                    .setProgress(vidLength, 0, false)
                    .setSmallIcon(R.mipmap.ic_launcher);

            int noteID = TAG.hashCode();
            startForeground(noteID, noteBuilder.build());
            NotificationManager noteMgr = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            //update every 3s or 4 times in the video, whichever is shorter
            int sleepTime = Math.min(3000, (int)((double)vidLength/4));
            while(mediaPlayer.isPlaying()) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Log.d(TAG, "sleep failure");
                }
                noteBuilder.setProgress(vidLength, mediaPlayer.getCurrentPosition(), false);
                noteMgr.notify(noteID, noteBuilder.build());
            }
            noteBuilder.setProgress(0, 0, false);//remove bar
            noteMgr.cancel(noteID);
        }
    }
/*
    private class ListenerThread extends Thread implements AudioManager.OnAudioFocusChangeListener {
        @Override
        public void onAudioFocusChange(int focusChange) {

        }
    }*/


    private class EndListener implements MediaPlayer.OnCompletionListener {
        private WifiManager.WifiLock wl;
        public EndListener(WifiManager.WifiLock wifiLock) {
            this.wl = wifiLock;
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            wl.release();//release wifilock
            stopForeground(true);//remove ongoing notification
            stopSelf();
            mp.release();
            //todo:release cpu lock
        }
    }
}
