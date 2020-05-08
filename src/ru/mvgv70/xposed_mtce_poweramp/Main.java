package ru.mvgv70.xposed_mtce_poweramp;

import ru.mvgv70.utils.IniFile;
import ru.mvgv70.utils.Utils;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.microntek.CarManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage
{
  private static final String PACKAGE_NAME = "com.maxmpz.audioplayer";
  private static String title = "";
  private static String artist = "";
  private static String album = "";
  private static String fileName = "";
  private static int id = -1;
  private static int position = -1;
  private static int duration = -1;
  private static int list_pos = -1;
  private static int list_size = -1;
  private static Context playerService = null;
  private static String playerPackageName = null;
  private static String packageVersion;
  private static boolean playing = false;
  private static boolean stopByMicrontek = false;
  private static String activeClassName = "";
  private static String EXTERNAL_SD = "/mnt/external_sd/";
  private static final String MAIN_SECTION = "settings";
  private static final String KEYS_SECTION = "keys";
  private static final String TOAST_SECTION = "toast";
  private static final String INI_FILE_NAME = "mtce-utils/poweramp.ini";
  private static IniFile props = new IniFile();
  private static CarManager cm = null;
  private static TickHandler handler = null;
  // bluetooth
  private static final String BLUETOOTH_STATE = "connect_state";
  private static final int BLUETOOTH_CALL_END = 1;
  private static final int BLUETOOTH_CALL_OUT = 2;
  private static final int BLUETOOTH_CALL_IN = 3;
  private static boolean playBeforeCall = false;
  // настройки
  private static boolean widgetKeys = true;
  private static boolean pauseOnCall = true;
  private static boolean sendAlbumArt = true;
  private static boolean pauseOnSleep = true;
  private static int play_pause_key = 0;
  private static int next_track_key = 0;
  private static int prev_track_key = 0;
  private static boolean toastEnable = false;
  private static int toastSize = 0;
  private static String toastFormat;
  // константы PowerAmp
  private static final int CMD_API_PAUSE = 2;
  private static final int CMD_API_PLAY_PAUSE = 1;
  private static final int CMD_API_NEXT = 4;
  private static final int CMD_API_PREVIOUS = 5;
  private final static String TAG = "xposed-mtce-poweramp";
  
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {
    
    // PlayerService.onCreate()
    XC_MethodHook onCreateService = new XC_MethodHook() {

      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onCreate");
        playerService = (Context)param.thisObject;
        playerPackageName = playerService.getPackageName();
        activeClassName = playerService.getPackageName();
        cm = new CarManager();
        // показать версию модуля
        try 
        {
          Context playerContext = playerService.createPackageContext(getClass().getPackage().getName(), Context.CONTEXT_IGNORE_SECURITY);
          String version = playerContext.getString(R.string.app_version_name);
          Log.i(TAG,"version="+version);
        } catch (Exception e) {}
        // версия Android
        Log.i(TAG,"android "+Build.VERSION.RELEASE);
        // версия PowerAmp
        packageVersion = playerService.getPackageManager().getPackageInfo(playerPackageName, 0).versionName;
        Log.i(TAG,"PowerAmp "+packageVersion);
        // расположение настроечного файла из build.prop
        EXTERNAL_SD = Utils.getModuleSdCard();
        // настройки
        readSettings();
        // create receivers
        createReceivers();
        // обработчик нажатий
        createKeyHandler();
      }
    };
    
    // PlayerService.onDestroy()
    XC_MethodHook onDestroyService = new XC_MethodHook() {

      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onDestroy");
        // выключаем Receivers
        playerService.unregisterReceiver(powerAmpReceiver);
        playerService.unregisterReceiver(tagsQueryReceiver);
        playerService.unregisterReceiver(microntekReceiver);
        playerService.unregisterReceiver(widgetReceiver);
        playerService.unregisterReceiver(bluetoothReceiver);
        playerService.unregisterReceiver(screenOffReceiver);
        //
        handler.removeMessages(1);
        handler = null;
        playerService = null;
        activeClassName = "";
        playing = false;
        stopByMicrontek = false;
      }
    };
    
    // start hooks
    if (!lpparam.packageName.equals(PACKAGE_NAME)) return;
    Log.d(TAG,PACKAGE_NAME);
    Utils.readXposedMap();
    Utils.setTag(TAG);
    Utils.findAndHookMethodCatch("com.maxmpz.audioplayer.player.PlayerService", lpparam.classLoader, "onCreate", onCreateService);
    Utils.findAndHookMethodCatch("com.maxmpz.audioplayer.player.PlayerService", lpparam.classLoader, "onDestroy", onDestroyService);
    Log.d(TAG,PACKAGE_NAME+" hook OK");
  }
  
  // чтение настроек
  private void readSettings()
  {
    try
    {
      Log.d(TAG,"read settings from "+EXTERNAL_SD+INI_FILE_NAME);
      props.clear();
      props.loadFromFile(EXTERNAL_SD+INI_FILE_NAME);
    } 
    catch (Exception e) 
    {
      Log.w(TAG,e.getMessage());
    }
    // настройки
    widgetKeys = props.getBoolValue(MAIN_SECTION, "widget.keys", true);
    Log.d(TAG,"widget.keys="+widgetKeys);
    pauseOnCall = props.getBoolValue(MAIN_SECTION, "pause_on_call", false);
    Log.d(TAG,"pause_on_call="+pauseOnCall);
    sendAlbumArt = props.getBoolValue(MAIN_SECTION, "albumart", true);
    Log.d(TAG,"albumart="+sendAlbumArt);
    pauseOnSleep = props.getBoolValue(MAIN_SECTION, "pause_on_sleep", false);
    Log.d(TAG,"pause_on_sleep="+pauseOnSleep);
    // toast
    toastEnable = props.getBoolValue(TOAST_SECTION, "enable", false);
    Log.d(TAG,"toast.enabled="+toastEnable);
    toastSize = props.getIntValue(TOAST_SECTION, "size", 0);
    Log.d(TAG,"toast.size="+toastSize);
    toastFormat = props.getValue(TOAST_SECTION, "format", "%title%");
    Log.d(TAG,"toast.format="+toastFormat);
    // клавиши
    play_pause_key = props.getIntValue(KEYS_SECTION, "play_pause", 0);
    Log.d(TAG,"play_pause="+play_pause_key);
    next_track_key = props.getIntValue(KEYS_SECTION, "next_track", 0);
    Log.d(TAG,"next_track="+next_track_key);
    prev_track_key = props.getIntValue(KEYS_SECTION, "prev_track", 0);
    Log.d(TAG,"prev_track="+prev_track_key);
  }

  // createReceivers
  private static void createReceivers()
  {
    // события PowerAmp
    IntentFilter pi = new IntentFilter();
    pi.addAction("com.maxmpz.audioplayer.TRACK_CHANGED");
    pi.addAction("com.maxmpz.audioplayer.STATUS_CHANGED");
    pi.addAction("com.maxmpz.audioplayer.AA_CHANGED");
    pi.addAction("com.maxmpz.audioplayer.TPOS_SYNC");
    playerService.registerReceiver(powerAmpReceiver, pi);
    Log.d(TAG,"poweramp event receiver created");
    // обработчик запросов скринсейвера
    IntentFilter qi = new IntentFilter();
    qi.addAction("hct.music.info");
    playerService.registerReceiver(tagsQueryReceiver, qi);
    Log.d(TAG,"screensaver request receiver created");
    // запуск штатных приложений
    IntentFilter mi = new IntentFilter();
    mi.addAction("com.microntek.bootcheck");
    playerService.registerReceiver(microntekReceiver, mi);
    Log.d(TAG,"bootcheck receiver created");
    // обработчик команд виджета
    IntentFilter wi = new IntentFilter();
    wi.addAction("hct.music.last");
    wi.addAction("hct.music.next");
    wi.addAction("hct.music.playpause");
    playerService.registerReceiver(widgetReceiver, wi);
    Log.d(TAG,"widget command receiver created");
    // bluetooth
    IntentFilter bi = new IntentFilter();
    bi.addAction("com.microntek.bt.report");
    playerService.registerReceiver(bluetoothReceiver, bi);
    Log.d(TAG,"bluetooth receiver created");
    // screen_off
    IntentFilter si = new IntentFilter();
    si.addAction("android.intent.action.SCREEN_OFF");
    playerService.registerReceiver(screenOffReceiver, si);
    Log.d(TAG,"screen off receiver created");
    // handler
    handler = new TickHandler();
    handler.sendEmptyMessageDelayed(1, 1000L);
    Log.d(TAG,"tick handler created");
  }
  
  // создание обработчика нажатий
  private void createKeyHandler()
  {
    cm.attach(new KeyHandler(), "KeyDown");
    Log.d(TAG,"KeyHandler created");
  }
  
  // обработчик нажатий
  @SuppressLint("HandlerLeak")
  private class KeyHandler extends Handler
  {
    public void handleMessage(Message msg)
    {
      Bundle data = msg.getData();
      int keyCode = data.getInt("value");
      Log.d(TAG,"keyCode="+keyCode);
      // кнопки обрабатываются при активном PowerAmp или в режиме воспроизведения
      if (playerPackageName.equals(activeClassName) || playing)
      {
        // для работающего PowerAmp
        if (keyCode == play_pause_key)
        {
          if (playing)
            stopPlayer();
          else
            startPlayer();
        }
        else if (keyCode == next_track_key)
        {
          if (playing) nextTrack();
        }
        else if (keyCode == prev_track_key)
        {
          if (playing) prevTrack();
        }
      }
    }
  };
  
  // отправка информации о воспроизведении
  private static void sendNotifyIntent(Context context)
  {
    Log.d(TAG,"sendNotifyIntent");
    //
    Intent intent = new Intent("com.microntek.music.report");
    intent.putExtra("type", "music.tags");
    intent.putExtra(MediaStore.Audio.AudioColumns.TITLE, title);
    intent.putExtra(MediaStore.Audio.AudioColumns.ALBUM, album);
    intent.putExtra(MediaStore.Audio.AudioColumns.ARTIST, artist);
    intent.putExtra(MediaStore.Audio.AudioColumns.DATA, fileName);
    intent.putExtra(MediaStore.Audio.AudioColumns._ID, id);
    intent.putExtra("class", PACKAGE_NAME);
    context.sendBroadcast(intent);
    // заголовок
    Intent tintent = new Intent("com.microntek.music.report");
    tintent.putExtra("type", "music.title");
    tintent.putExtra("value", title);
    tintent.putExtra("class", PACKAGE_NAME);
    context.sendBroadcast(tintent);
    // картинка
    if (sendAlbumArt == false)
    {
      // если не отсылаем картинку на виджет, то пошлем пустые id
      Intent pintent = new Intent("com.microntek.music.report");
      pintent.putExtra("type", "music.alumb");
      pintent.putExtra("value", new long[] {-1, -1});
      pintent.putExtra("class", PACKAGE_NAME);
      context.sendBroadcast(pintent);
    }
  }
  
  private static void sendMusicOn()
  {
    Log.d(TAG,"sendMusicOn");
    // music on
    Intent intent = new Intent("com.microntek.canbusdisplay");
    intent.putExtra("type", "music-on");
    playerService.sendBroadcast(intent);
    // state=1
    Intent sintent = new Intent("com.microntek.music.report");
    sintent.putExtra("type", "music.state");
    sintent.putExtra("value", 1);
    sintent.putExtra("class", PACKAGE_NAME);
    playerService.sendBroadcast(sintent);
  }
  
  private static void sendMusicOff()
  {
    Log.d(TAG,"sendMusicOff");
    // music off
    Intent intent = new Intent("com.microntek.canbusdisplay");
    intent.putExtra("type", "music-off");
    playerService.sendBroadcast(intent);
    // state=0
    Intent sintent = new Intent("com.microntek.music.report");
    sintent.putExtra("type", "music.state");
    sintent.putExtra("value", 0);
    sintent.putExtra("class", PACKAGE_NAME);
  }
  
  private static void sendCanBusInfo()
  {
    Log.d(TAG,"sendCanBusInfo");
    // canbus
    Intent intent = new Intent("com.microntek.canbusdisplay");
    intent.putExtra("type", "music");
    intent.putExtra("all", list_size);
    intent.putExtra("cur", list_pos+1);
    intent.putExtra("time", position);
    playerService.sendBroadcast(intent);
    // position=0
    Intent tintent = new Intent("com.microntek.music.report");
    tintent.putExtra("type", "music.time");
    tintent.putExtra("value", new int[] {position, duration});
    tintent.putExtra("class", PACKAGE_NAME);
    playerService.sendBroadcast(tintent);
  }
  
  private static void turnMtcAppsOff()
  {
    Log.d(TAG,"turn Microntek apps off");
    Intent intent = new Intent("com.microntek.bootcheck");
    intent.putExtra("class", playerPackageName);
    playerService.sendBroadcast(intent);
  }
  
  // показать уведомление о смене трека
  private static void showToast()
  {
    Log.d(TAG,"showToast");
    Intent intent = new Intent("com.microntek.music.toast");
    intent.putExtra("toast.size", toastSize);
    intent.putExtra("toast.format", toastFormat);
    intent.putExtra("class", PACKAGE_NAME);
    intent.putExtra(MediaStore.Audio.AudioColumns.TITLE, title);
    intent.putExtra(MediaStore.Audio.AudioColumns.ALBUM, album);
    intent.putExtra(MediaStore.Audio.AudioColumns.ARTIST, artist);
    intent.putExtra(MediaStore.Audio.AudioColumns.DATA, fileName);
    playerService.sendBroadcast(intent);
  }
  
  // информация с картинкой
  private static void SendBitmapInfo(Bitmap bitmap)
  {
    Log.d(TAG,"SendBitmapInfo");
    if (bitmap == null) Log.w(TAG,"bitmap == null");
    Intent intent = new Intent("com.microntek.music.report");
    intent.putExtra("type", "music.albumart");
    intent.putExtra("value", bitmap);
    intent.putExtra("class", playerPackageName);
    playerService.sendBroadcast(intent);
  }
  
  // команда PowerAmp
  private static void commandPowerAmp(int cmd)
  {
    Log.d(TAG,"send com.maxmpz.audioplayer.API_COMMAND, cmd="+cmd);
    Intent intent = new Intent("com.maxmpz.audioplayer.API_COMMAND");
    intent.setComponent(new ComponentName("com.maxmpz.audioplayer","com.maxmpz.audioplayer.player.PlayerService"));
    intent.putExtra("cmd",cmd);
    playerService.startService(intent); 
  }
  
  // остановка проигрывания
  private static void stopPlayer()
  {
    commandPowerAmp(CMD_API_PAUSE);
  }
  
  // начало проигрывания
  private static void startPlayer()
  {
    if (!playing) commandPowerAmp(CMD_API_PLAY_PAUSE);
  }
  
  // следующий трек
  private static void nextTrack()
  {
    commandPowerAmp(CMD_API_NEXT);
  }
  
  // предыдущий трек
  private static void prevTrack()
  {
    commandPowerAmp(CMD_API_PREVIOUS);
  }
  
  // обработчик событий PowerAmp
  private static BroadcastReceiver powerAmpReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction();
      Log.d(TAG,"PowerAmp: "+action);
      if (action.equals("com.maxmpz.audioplayer.TRACK_CHANGED"))
      {
        Bundle mCurrentTrack = intent.getBundleExtra("track");
        // сохраним информацию о треке
        title = mCurrentTrack.getString("title");
        artist = mCurrentTrack.getString("artist");
        album = mCurrentTrack.getString("album");
        fileName = mCurrentTrack.getString("path");
        id = mCurrentTrack.getInt("id");
        list_size = mCurrentTrack.getInt("listSize");
        list_pos = mCurrentTrack.getInt("posInList");
        duration = mCurrentTrack.getInt("dur")*1000;
        position = 0;
        // log
        Log.d(TAG,"id="+id);
        Log.d(TAG,"title="+title);
        Log.d(TAG,"album="+album);
        Log.d(TAG,"artist="+artist);
        Log.d(TAG,"filename="+fileName);
        Log.d(TAG,"duration="+duration);
        // информация
        sendNotifyIntent(context);
        sendCanBusInfo();
        if (playing)
        {
          if (toastEnable) showToast();
        }
        Log.d(TAG,"playing="+playing);
      }
      else if (action.equals("com.maxmpz.audioplayer.STATUS_CHANGED"))
      {
        // определяем состояние проигрывания
        if (intent.hasExtra("pos"))
        {
          position = intent.getIntExtra("pos",0)*1000;
        }
        if (intent.hasExtra("paused"))
        {
          boolean prev_playing = playing;
          playing = !intent.getBooleanExtra("paused", false);
          Log.d(TAG,"playing="+playing);
          Log.d(TAG,"prev_playing="+prev_playing);
          Log.d(TAG,"stopByMicrontek="+stopByMicrontek);
          if (playing)
          {
            stopByMicrontek = false;
            // разослать интент о закрытии штатных приложений
            turnMtcAppsOff();
            // информация
            sendMusicOn();
            sendNotifyIntent(context);
            sendCanBusInfo();
          }
          else if ((stopByMicrontek == false) && prev_playing)
            // посылаем сигнал выключения если раньше было проигрывание и не установлен флаг старта другого штатного приложения
            sendMusicOff();
        }
      }
      else if (action.equals("com.maxmpz.audioplayer.TPOS_SYNC"))
      {
        position = intent.getIntExtra("pos",0)*1000;
        Log.d(TAG,"position="+position);
        Log.d(TAG,"playing="+playing);
        // canbus info
        if (playing)
          sendCanBusInfo();
      }
      else if (action.equals("com.maxmpz.audioplayer.AA_CHANGED"))
      {
        SendBitmapInfo((Bitmap)intent.getParcelableExtra("aaBitmap"));
      }
    }
  };
  
  // обработчик com.android.music.querystate, запросы от скринсейвера
  private static BroadcastReceiver tagsQueryReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      // отправить информацию
      Log.d(TAG,"PowerAmp: tags query receiver, playing="+playing);
      if (playing) sendNotifyIntent(context);
    }
  };
  
  // com.microntek.bootcheck
  private static BroadcastReceiver microntekReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String className = intent.getStringExtra("class");
      activeClassName = className;
      Log.d(TAG,"com.microntek.bootcheck, class="+className);
      if (!className.equals(playerPackageName))
      {
        stopByMicrontek = true;
        Log.d(TAG,"playing="+playing);
        // запускается штатная программа, выключим PowerAmp
        if (playing) stopPlayer();
      }
    }
  };
  
  // hct.music.*
  private static BroadcastReceiver widgetReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction();
      Log.d(TAG,"hct music action: "+action);
      if (!widgetKeys) return;
      // кнопки обрабатываются при активном PowerAmp или в режиме воспроизведения
      if (playerPackageName.equals(activeClassName) || playing)
      {
        // для работающего PowerAmp
        if (action.equals("hct.music.playpause"))
        {
          if (playing)
            stopPlayer();
          else
            startPlayer();
        }
        else if (action.equals("hct.music.next"))
          nextTrack();
        else if (action.equals("hct.music.last"))
          prevTrack();
      }
    }
  };
  
  // обработчик bluetooth
  private static BroadcastReceiver bluetoothReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      int state = intent.getIntExtra(BLUETOOTH_STATE, -1);
      Log.d(TAG,"bluetooth.state="+state);
      if (pauseOnCall == false) return;
      if (state == BLUETOOTH_CALL_IN)
      {
        playBeforeCall = playing;
        if (playing) stopPlayer();
      }
      else if (state == BLUETOOTH_CALL_OUT)
      {
        playBeforeCall = playing;
        if (playing) stopPlayer();
      }
      else if (state == BLUETOOTH_CALL_END)
      {
        if (playBeforeCall && !playing) startPlayer();
      }
    }
  };
  
  // обработчик screen off
  private static BroadcastReceiver screenOffReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      Log.d(TAG,"sleep: screen off");
      if (pauseOnSleep && playing) stopPlayer();
    }
  };

  // время проигрывания
  private static class TickHandler extends Handler
  {
    public void handleMessage(Message msg)
    {
      if (playing)
      {
        position+=1000;
        // update canbus info
        sendCanBusInfo();
      }
      sendEmptyMessageDelayed(1, 1000L);
    }
  };

}
