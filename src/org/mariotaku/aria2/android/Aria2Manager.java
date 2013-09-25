package org.mariotaku.aria2.android;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.mariotaku.aria2.Aria2API;
import org.mariotaku.aria2.DownloadUris;
import org.mariotaku.aria2.GlobalStat;
import org.mariotaku.aria2.Options;
import org.mariotaku.aria2.Version;
import org.mariotaku.aria2.Status;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.os.Process;

public class Aria2Manager implements Aria2UIMessage,Aria2APIMessage
{
	private Aria2API _aria2 = null;
	private String _aria2Host = null;
	private Context _context = null;
	
	private Timer mGlobalStatRefreshTimer = null;
	private Handler _mRefreshHandler = null;
	
	public Handler _mHandler = null;
	HandlerThread _aria2APIHandlerThread = null;
	
	public Aria2Manager(Context context,Handler mRefreshHandler)
	{
		Log.i("aria2", "init Aria2Manager!");
		_context = context;
		_mRefreshHandler = mRefreshHandler;
		_aria2APIHandlerThread = new HandlerThread("Aria2 API Handler Thread"); 
		
		_aria2APIHandlerThread.start();
		
		
		Looper mLooper = _aria2APIHandlerThread.getLooper(); 
		_mHandler = new Handler(mLooper)
		{
			public void handleMessage(Message msg)
			{
				Message sendToUIThreadMsg = new Message();
				Log.i("aria2", "aria2 manager handler get msg:" + msg.what);
				try
				{
					switch (msg.what)
					{
					case GET_GLOBAL_STAT:
						sendToUIThreadMsg.what = GLOBAL_STAT_REFRESHED;
						GlobalStat stat = _aria2.getGlobalStat();
						sendToUIThreadMsg.obj = stat;
						
						//get waiting and stopped task count 
						sendToAria2APIHandlerMsg(GET_ALL_STATUS,stat);
						
						_mRefreshHandler.sendMessage(sendToUIThreadMsg);
						break;
					case GET_VERSION_INFO:
						sendToUIThreadMsg.what = VERSION_INFO_REFRESHED;
						sendToUIThreadMsg.obj = GetVersionInfo();
						_mRefreshHandler.sendMessage(sendToUIThreadMsg);
						break;
					case GET_SESSION_INFO:
						sendToUIThreadMsg.what = SESSION_INFO_REFRESHED;
						sendToUIThreadMsg.obj = GetSessionInfo();
						_mRefreshHandler.sendMessage(sendToUIThreadMsg);
						break;
					case ADD_URI:
						sendToUIThreadMsg.what = DOWNLOAD_INFO_REFRESHED;
						if(msg.obj == null)
						{
							sendToUIThreadMsg.obj = AddUri();
						}
						else
						{
							sendToUIThreadMsg.obj = AddUri((String)msg.obj);
						}
						_mRefreshHandler.sendMessage(sendToUIThreadMsg);
						break;
					case GET_ALL_STATUS:
						sendToUIThreadMsg.what = ALL_STATUS_REFRESHED;
						if(msg.obj == null)
						{
							return;
						}
						GlobalStat statNew = (GlobalStat) msg.obj;
						ArrayList<Status> activeList = _aria2.tellActive();
						ArrayList<Status> waitingList = _aria2.tellWaiting(0,Integer.valueOf(statNew.numWaiting));
						ArrayList<Status> stopList = _aria2.tellStopped(0,Integer.valueOf(statNew.numStopped));
						
						ArrayList<ArrayList<Status>> list = new ArrayList<ArrayList<Status>>();
						
						list.add(activeList);
						list.add(waitingList);
						list.add(stopList);
						
						sendToUIThreadMsg.obj = list;
						_mRefreshHandler.sendMessage(sendToUIThreadMsg);
						break;
					case PAUSE_ALL_DOWNLOAD:
						_aria2.pauseAll();
						break;
					case RESUME_ALL_DOWNLOAD:
						_aria2.unpauseAll();
						break;
					case PURGE_DOWNLOAD:
						_aria2.purgeDownloadResult();
						break;
					}
				}
				catch (Exception e)
				{
					Log.e("aria2", "aria2 manager handler is error!",e);
					
					handlerError(msg.what,sendToUIThreadMsg);
				}

			}

			private void handlerError(int comeMessage,Message sendToUIThreadMsg)
			{
				String errorInfo = "aria2 network errors!";
				switch (comeMessage)
				{
					case GET_GLOBAL_STAT:
						errorInfo = "get aria2 global stat error!please check setting!";
						sendErrorInfoToUiThreadAndStopUpdateGlobalStat(sendToUIThreadMsg, errorInfo);
						break;
					case ADD_URI:
						errorInfo = "add uri error!";
						sendErrorInfoToUiThread(sendToUIThreadMsg, errorInfo);
						break;
						
				}
				
			}

			private void sendErrorInfoToUiThreadAndStopUpdateGlobalStat(Message sendToUIThreadMsg,
					String errorInfo)
			{
				sendToUIThreadMsg.what = SHOW_ERROR_INFO_STOP_UPDATE_GLOBAL_STAT;
				sendToUIThreadMsg.obj = errorInfo;
				_mRefreshHandler.sendMessage(sendToUIThreadMsg);
			}	
			
			private void sendErrorInfoToUiThread(Message sendToUIThreadMsg,
					String errorInfo)
			{
				sendToUIThreadMsg.what = SHOW_ERROR_INFO;
				sendToUIThreadMsg.obj = errorInfo;
				_mRefreshHandler.sendMessage(sendToUIThreadMsg);
			}
			
			
		};
		
		Process.setThreadPriority(_aria2APIHandlerThread.getThreadId(),Process.THREAD_PRIORITY_BACKGROUND);
		
	}

	public void InitHost() {
		Log.i("aria2", "init host!");
		String nowAria2Host = getHost();
		if(!nowAria2Host.equals(_aria2Host))
		{
			Log.i("aria2", "host should change!");
			_aria2Host = nowAria2Host;
			try
			{
				_aria2 = new Aria2API(_aria2Host);
				
			}catch(Exception e)
			{
				_aria2 = null;
				throw new IllegalArgumentException("Aria2 host is error!");
				
			}
			
		}
	}
	
	public void StartUpdateGlobalStat()
	{
		Log.i("aria2", "start update global stat!");
		checkAria2();
		mGlobalStatRefreshTimer = new Timer();
		mGlobalStatRefreshTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				sendToAria2APIHandlerMsg(GET_GLOBAL_STAT);
			}

		}, 0, 1000);
	}
	
	public void sendToAria2APIHandlerMsg(int msgType)
	{
		Message sendToAria2APIHandlerMsg = new Message();
		sendToAria2APIHandlerMsg.what = msgType;
		_mHandler.sendMessage(sendToAria2APIHandlerMsg);
	}
	
	public void sendToAria2APIHandlerMsg(int msgType,Object msgObj)
	{
		Message sendToAria2APIHandlerMsg = new Message();
		sendToAria2APIHandlerMsg.what = msgType;
		sendToAria2APIHandlerMsg.obj = msgObj;
		_mHandler.sendMessage(sendToAria2APIHandlerMsg);
	}
	
	public void StopUpdateGlobalStat()
	{
		if(mGlobalStatRefreshTimer != null)
		{
			mGlobalStatRefreshTimer.cancel();
			mGlobalStatRefreshTimer = null;
			Log.i("aria2", "aria2 stop update GlobalStat timer!");
		}
	}
	
	private String GetVersionInfo()
	{
		checkAria2();
		
		StringBuilder version = new StringBuilder();
		Version versionInfo = _aria2.getVersion();
		version.append("Version : " + versionInfo.version + "\n");
		Object[] values = versionInfo.enabledFeatures;
		StringBuilder features = new StringBuilder();
		for (Object value : values) {
			features.append(value + "\n");
		}
		features.delete(features.length() - 1, features.length());
		version.append("Enabled features : \n" + features.toString());
		
		return version.toString();
	}
	
	private String GetSessionInfo()
	{
		checkAria2();
		
		StringBuilder session_info = new StringBuilder();
		session_info.append("Session ID : " + _aria2.getSessionInfo().sessionId);
		return session_info.toString();
	}

	
	
	private String GetStatus()
	{
		checkAria2();
		return String.valueOf(_aria2.tellStatus("7", "gid").gid);
	}
	
	private String AddUri()
	{
		checkAria2();
		String returnValue = _aria2.addUri(
							new DownloadUris(
									"http://releases.ubuntu.com/11.10/ubuntu-11.10-desktop-i386.iso.torrent"));	
		return "Return value : " + returnValue;
	}
	
	private String AddUri(String uri)
	{
		checkAria2();
		String returnValue = _aria2.addUri(
							new DownloadUris(
									uri));	
		return "Return value : " + returnValue;
	}
	
	private String getHost() {
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(_context);
		String prefKeyHost= sharedPref.getString(SettingsActivity.PREF_KEY_HOST,"");
		if(prefKeyHost == null)
		{
			throw new IllegalArgumentException("host address is null!");
		}
		
		if(prefKeyHost.equals(""))
		{
			throw new IllegalArgumentException("pealse initial host address!");
		}
		
		return prefKeyHost;
	}	
	
	private void checkAria2()
	{
		if(_aria2 == null)
		{
			throw new IllegalArgumentException("Aria2 init is error!please check setting!");
		}
	}
	
	


}
