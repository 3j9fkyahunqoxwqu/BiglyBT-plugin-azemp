package com.vuze.mediaplayer.mplayer;


import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Listener;
import com.biglybt.core.internat.MessageText;
import com.biglybt.core.logging.LogAlert;
import com.biglybt.core.logging.Logger;
import com.biglybt.core.util.Debug;
import org.opensubtitles.api.OpenSubtitlesAPI;

import com.vuze.mediaplayer.Language;
import com.vuze.mediaplayer.LanguageSource;
import com.vuze.mediaplayer.MediaPlaybackState;
import com.vuze.mediaplayer.MetaDataListener;
import com.vuze.mediaplayer.PlayerPreferences;
import com.vuze.mediaplayer.PositionListener;
import com.vuze.mediaplayer.StateListener;
import com.vuze.mediaplayer.VolumeListener;
import com.vuze.mediaplayer.swt.AspectRatioData;
import com.vuze.mediaplayer.swt.BaseMediaPlayerSWT;
import com.vuze.mediaplayer.swt.PlayerFrame;
import com.vuze.mediaplayer.swt.RenderFrame;
import com.vuze.swt.MPlayerRendererCanvas;


public class MPlayer extends BaseMediaPlayerSWT {

	public static void initialise(File path) {
		MPlayerInstance.initialise(path);
	}

	private PlayerFrame		player_frame;
	private RenderFrame		render_frame;
	
	private List<String> output;
	
	private volatile boolean disposed = false;
	
	private Thread outputParser;
		
	public MPlayer() {
		this(null);
	}
	
	private boolean firstLengthReceived = false;
	private boolean firstVolumeReceived = false;
	
	public MPlayer(PlayerPreferences preferences) {
	
		super(preferences);
		
		LogAlert alert = new LogAlert( this, LogAlert.UNREPEATABLE, LogAlert.AT_WARNING, MessageText.getString("azemp.vlc.switch" ));

		alert.forceNotify = true;

		Logger.log( alert );
		
		output = new LinkedList<String>();
		
		outputParser = new Thread("MPlayer output parser") {
			@Override
			public void run() {
				try {
					while(!disposed) {
						String line = null;
						synchronized (output) {
							if(!output.isEmpty()) {
								
								line = output.remove(0);	
																
							} else {
								output.wait();
							}
						}
						
						if ( line != null ){
							
							//System.out.println(line);
							
							try{
								parseOutput(line);
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}
				} catch (Throwable e) {
					
					Debug.out( e );
				}
			};
		};
		outputParser.setDaemon(true);
		outputParser.start();
		
		
	}
	
	private static final String ANS_LENGTH = "ANS_LENGTH=";
	private static final String ANS_POSITION = "ANS_TIME_POSITION=";
	private static final String ANS_VOLUME = "ANS_VOLUME=";
	private static final String ANS_SUB = "ANS_SUB=";
	
	private static final String ANS_WIDTH = "ANS_WIDTH=";
	private static final String ANS_HEIGHT = "ANS_HEIGHT=";
	private static final String ANS_ASPECT = "ANS_ASPECT=";
	
	
	private static final String ID_VIDEO_ASPECT = "ID_VIDEO_ASPECT=";
	
	private static final String ID_AUDIO_ID = "ID_AUDIO_ID=";
	private static final String ID_SUBTITLE_ID = "ID_SUBTITLE_ID=";
	
	private static final String ID_AUDIO_TRACK = "ID_AUDIO_TRACK=";
	private static final String ID_SUBTITLE_TRACK = "ID_SUBTITLE_TRACK=";
	
	private static final String ID_FILE_SUB_ID = "ID_FILE_SUB_ID=";
	private static final String ID_FILE_SUB_FILENAME = "ID_FILE_SUB_FILENAME=";
	
	private static final String ID_EXIT = "ID_EXIT=";
	
	private static final Pattern v_timeInfo = Pattern.compile("A:\\s*([0-9\\.]+) V:\\s*[0-9\\.]* .*");
	private static final Pattern a_timeInfo = Pattern.compile("A:\\s*([0-9\\.]+) .*");
	
	private MPlayerInstance	current_instance;
	
	private boolean parsingLanguage;
	private boolean isAudioTrack;
	private Language language;
	
	private int width;
	private float aspect;
	
	@Override
	public boolean isVLC() {
		return( false );
	}
	
	private void parseOutput(String line) {
		boolean stillParsing = false;
			
		//if ( !line.startsWith( "A:")){
		//	System.out.println(line);
		//}
		Matcher v_matcher = v_timeInfo.matcher(line);
		Matcher a_matcher = a_timeInfo.matcher(line);
		if(v_matcher.matches()) {
			float time = Float.parseFloat(v_matcher.group(1));
			MPlayerInstance instance = getCurrentInstance();
			
			if ( instance != null ){
				instance.positioned(time);
			}
			reportPosition(time);
		} else if(a_matcher.matches()) {
			float time = Float.parseFloat(a_matcher.group(1));
			MPlayerInstance instance = getCurrentInstance();
			
			if ( instance != null ){
				instance.positioned(time);
			}
			reportPosition(time);
		} else		
		if(line.startsWith("VIDEO:")) {
			Pattern p = Pattern.compile(".*?([0-9]+)x([0-9]+).*?");
			Matcher m = p.matcher(line);
			if(m.matches()) {
				int width = Integer.parseInt(m.group(1));
				int height = Integer.parseInt(m.group(2));
				
				if(metaDataListener != null) {
					player_frame.setAspectRatio((float)width / (float)height);
				}
			}
		} else
		if(line.startsWith("Starting playback...")) {
			//Ok, so the file is initialized, let's gather information
			stateListener.stateChanged(MediaPlaybackState.Playing);
			
			MPlayerInstance instance = getCurrentInstance();
			
			if ( instance != null ){
			
				instance.initialised();
			}
			
			reportNewState(MediaPlaybackState.Playing);
		} else
		if(line.startsWith(ANS_POSITION)) {
			try {
				MPlayerInstance instance = getCurrentInstance();
				
				if ( instance != null ){
					instance.positioned();
				}
				
				float position = Float.parseFloat(line.substring(ANS_POSITION.length()));
				reportPosition(position);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_LENGTH)) {
			try {
				float duration = Float.parseFloat(line.substring(ANS_LENGTH.length()));
				if(!firstLengthReceived) {
					firstLengthReceived = true;
					if(preferences != null) {
						float seekTo = preferences.getPositionForFile(getOpenedFile()) - 2f;
						if(seekTo > 0 && seekTo < 0.99 * duration && seekTo < duration - 20f) {
							doSeek(seekTo);
						}
					}
				}
				reportDuration(duration);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_VOLUME)) {
			try {
				int volume = (int) Float.parseFloat(line.substring(ANS_VOLUME.length()));
				reportVolume(volume);
				if(!firstVolumeReceived) {
					firstVolumeReceived = true;
					if(preferences != null && preferences.getVolume() != volume) {
						setVolume(preferences.getVolume());
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ID_VIDEO_ASPECT)) {
			try {
				float aspect = Float.parseFloat(line.substring(ID_VIDEO_ASPECT.length()));
				if(aspect > 0) {
					player_frame.setAspectRatio(aspect);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_WIDTH)) {
			try {
				width = Integer.parseInt(line.substring(ANS_WIDTH.length()));
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_HEIGHT)) {
			try {
				int videoWidth = width;
				int videoHeight = Integer.parseInt(line.substring(ANS_HEIGHT.length()));
				
				int displayWidth = videoWidth;
				int displayHeight = videoHeight;
				
				if(aspect > 0 && abs(aspect-(float)videoWidth / (float)videoHeight) > 0.1) {
					displayWidth = (int) (displayHeight * aspect);
				}
				if(metaDataListener != null) {
					metaDataListener.receivedVideoResolution(videoWidth, videoHeight);
					metaDataListener.receivedDisplayResolution(displayWidth, displayHeight);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_ASPECT)) {
			try {
				aspect = Float.parseFloat(line.substring(ANS_ASPECT.length()));
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ID_AUDIO_ID)) {
			reportParsingDone();
			try {
				String audioId = line.substring(ID_AUDIO_ID.length());
				language = new Language(LanguageSource.STREAM,""+audioId);
				parsingLanguage = true;
				isAudioTrack = true;
				stillParsing = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ID_SUBTITLE_ID)) {
			reportParsingDone();
			try {
				String audioId = line.substring(ID_SUBTITLE_ID.length());
				language = new Language(LanguageSource.STREAM,""+audioId);
				parsingLanguage = true;
				isAudioTrack = false;
				stillParsing = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ID_FILE_SUB_ID)) {
			reportParsingDone();
			try {
				String subId = line.substring(ID_FILE_SUB_ID.length());
				language = new Language(LanguageSource.FILE,""+subId);
				parsingLanguage = true;
				isAudioTrack = false;
				stillParsing = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(parsingLanguage && (line.startsWith(ID_FILE_SUB_FILENAME))) {
			try {
				String fileName = line.substring(ID_FILE_SUB_FILENAME.length());
				try {
					File f = new File(fileName);
					language.setSourceInfo(f.getAbsolutePath());
					fileName = f.getName();
				} catch (Exception e) {
					e.printStackTrace();
				}
				language.setName(fileName);
				Locale locale = OpenSubtitlesAPI.getLocalSubtitleLanguage(getOpenedFile(), fileName);
				if(locale != null) {
					language.setLanguage(locale);
				}
				stillParsing = false;
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(parsingLanguage && (line.startsWith("ID_AID_" + language.getId() + "_NAME=") || line.startsWith("ID_SID_" + language.getId() + "_NAME=")) ) {
			String key = "ID_AID_" + language.getId() + "_NAME=";
			String name = line.substring(key.length());
			language.setName(name);
			stillParsing = true;
		} else
		if(parsingLanguage && (line.startsWith("ID_AID_" + language.getId() + "_LANG=") || line.startsWith("ID_SID_" + language.getId() + "_LANG=") )) {
			String key = "ID_AID_" + language.getId() + "_LANG=";
			String isoCode = line.substring(key.length());
			language.setLanguage(isoCode);
			stillParsing = true;
		} else
		if(parsingLanguage && ( line.startsWith("ID_AID_" + language.getId()) || line.startsWith("ID_SID_" + language.getId())) ) {
			stillParsing = true;
		} else
		if(line.startsWith(ID_AUDIO_TRACK)) {
			try {
				String audioId = line.substring(ID_AUDIO_TRACK.length());
				reportAudioTrackChanged(audioId);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ID_SUBTITLE_TRACK)) {
			try {
				String subtitleId = line.substring(ID_SUBTITLE_TRACK.length());
				reportSubtitleChanged(subtitleId,LanguageSource.STREAM);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else
		if(line.startsWith(ANS_SUB)) {
			try {
				String subtitleId = line.substring(ANS_SUB.length());
				reportSubtitleChanged(subtitleId,LanguageSource.STREAM);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}else if(line.startsWith("VDecoder init failed")) {
			
			MediaPlaybackState.Failed.setDetails( MessageText.getString( "azemp.failed.nocodec" ) );
			reportNewState(MediaPlaybackState.Failed);
		}else if(line.startsWith("<vo_direct3d>Reading display capabilities failed")) {
			
			MediaPlaybackState.Failed.setDetails( MessageText.getString( "azemp.failed.d3dbad" ) );
			reportNewState(MediaPlaybackState.Failed);
		}else if(line.startsWith(ID_EXIT)) {
			
			reportNewState(MediaPlaybackState.Closed);
		}

		//else System.out.println(line);
		
			
		if(parsingLanguage && ! stillParsing) {
			Language parsed = language;
			reportParsingDone();
			MPlayerInstance instance = getCurrentInstance();
			
			if ( instance != null ){

				if(instance.activateNextSubtitleLoaded) {
					instance.activateNextSubtitleLoaded = false;
					setSubtitles(parsed);
				}
			}
		}
	}
	
	private double abs(float f) {
		return f > 0 ? f : -f;
	}

	@Override
	public void doLoadSubtitlesFile(String file, boolean autoPlay) {
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){

			instance.doLoadSubtitlesFile( file, autoPlay );
		}
	}
	
	@Override
	public void showMessage(String message, int duration) {
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){

			instance.sendCommand("osd_show_text \"" + message + "\" " + duration + " " + 0);
		}
		
	}
	
	private void reportSubtitleChanged(String subtitleId,LanguageSource source) {
		if(metaDataListener != null) {
			metaDataListener.activeSubtitleChanged(subtitleId,source);
		}		
	}

	private void reportAudioTrackChanged(String audioId) {
		if(metaDataListener != null) {
			metaDataListener.activeAudioTrackChanged(audioId);
		}		
	}

	private void reportParsingDone() {
		if(parsingLanguage) {
			if(isAudioTrack) {
				reportFoundAudioTrack(language);
			} else {
				reportFoundSubtitle(language);
			}
			language = null;
			parsingLanguage = false;
			isAudioTrack = false;
		}
	}
	
	@Override
	public RenderFrame
	createRenderFrame(
		PlayerFrame			_player_frame,
		final Composite		parent )
	{
		player_frame	= _player_frame;
		
		render_frame = 
			new RenderFrame()
			{
				private MPlayerRendererCanvas canvas = new MPlayerRendererCanvas(parent,SWT.NONE);
				
				@Override
				public String[]
				getExtraOptions()
				{
					return( canvas.getExtraMplayerOptions());
				}
				
				@Override
				public void
				setVisible(
					boolean	vis )
				{
					canvas.setVisible( vis );
				}
				
				@Override
				public void
				setBackground(
					Color	c )
				{
					canvas.setBackground( c );
				}
				
				@Override
				public void
				setCursor(
					Cursor	c )
				{
					canvas.setCursor( c );
				}
				
				@Override
				public boolean
				isDisposed()
				{
					return( canvas.isDisposed());
				}
				
				@Override
				public void
				setLayoutData(
					AspectRatioData	ld )
				{
					canvas.setLayoutData( ld );
				}
				
				@Override
				public void
				addListener(
					int			eventType,
					Listener	listener )
				{
					canvas.addListener(eventType, listener);
				}
			};
		
		return( render_frame );
	}
	
	@Override
	public void
	doOpen(
		String fileOrUrl ) 
	{
		
		MPlayerInstance instance;
		
		synchronized( this ){
			
			doStop( false );
			
			instance = current_instance = new MPlayerInstance(preferences);
		}
		
		reportNewState(MediaPlaybackState.Opening);
		
		firstLengthReceived = false;
		firstVolumeReceived = false;
		
		instance.doOpen( 
			fileOrUrl, 
			render_frame.getExtraOptions(),
			new MPlayerInstance.OutputConsumer()
			{
				@Override
				public void
				consume(
					String line) 
				{
					synchronized (output) {
						output.add(line);
						output.notifyAll();
					}				
				}
			});
	}

	protected MPlayerInstance
	getCurrentInstance()
	{
		synchronized( this ){
			
			return( current_instance );
		}
	}
	
	@Override
	public void
	doPause() 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			instance.doPause();
		}
		
		reportNewState(MediaPlaybackState.Paused);
	}

	
	@Override
	public void
	doResume() 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){

			instance.doResume();
		}
		
		reportNewState(MediaPlaybackState.Playing);
	}

	@Override
	public boolean 
	canSeekAhead()
	{	
		return false;
	}
	
	@Override
	public void
	doSeek(
		float timeInSecs) 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){

			instance.doSeek( timeInSecs );
		}
	}

	
	@Override
	public void
	doSetVolume(
		int volume) 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			instance.doSetVolume(volume);
		}
		
		reportVolume(volume);
	}

	@Override
	public void
	mute(
		boolean		on )
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			instance.doMute( on );
		}
	}
	
	@Override
	public void
	doRedraw()
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			instance.doRedraw();
		}
	}
	
	@Override
	public void
	setAudioTrack(
		Language language) 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			instance.setAudioTrack(language);
		}
	}
	
	@Override
	public void
	setSubtitles(
		Language language) 
	{
		MPlayerInstance instance = getCurrentInstance();
		
		if ( instance != null ){
		
			reportSubtitleChanged( instance.setSubtitles(language),language != null ? language.getSource() : null);
		}
	}
	
	@Override
	public void
	doStop() 
	{
		doStop( true );
	}
	
	protected void 
	doStop(
		boolean	report_state ) 
	{		
		synchronized( this ){

			if ( current_instance != null ){
				
				if(preferences != null) {
					preferences.setPositionForFile(getOpenedFile(), getPositionInSecs());
				}
				
				current_instance.doStop();

				current_instance = null;
			}
			
			synchronized (output) {
				output.clear();
				output.notifyAll();
			}	
		}
		
		if ( report_state ){
		
			reportNewState(MediaPlaybackState.Stopped);
		}
	}

	private MetaDataListener metaDataListener;
	private StateListener stateListener;
	private VolumeListener volumeListener;
	private PositionListener positionListener;
	
	
	@Override
	public void setMetaDataListener(MetaDataListener listener) {
		this.metaDataListener = listener;
	}

	
	@Override
	public void setStateListener(StateListener listener) {
		this.stateListener = listener;
		
	}

	
	@Override
	public void setVolumeListener(VolumeListener listener) {
		this.volumeListener = listener;
		
	}
	
	
	@Override
	public void setPositionListener(PositionListener listener) {
		this.positionListener = listener;		
	}
	
	private void reportPosition(float position) {
		if(positionListener != null) {
			positionListener.positionChanged(position);
		}
	}
	
	private void reportVolume(int volume) {
		if(volumeListener != null) {
			volumeListener.volumeChanged(volume);
		}
	}
	
	private void reportDuration(float duration) {
		if(metaDataListener != null) {
			metaDataListener.receivedDuration(duration);
		}
	}
	
	private void reportFoundAudioTrack(Language audioTrack) {
		if(metaDataListener != null) {
			metaDataListener.foundAudioTrack(audioTrack);
		}
	}
	
	private void reportFoundSubtitle(Language subtitle) {
		if(metaDataListener != null) {
			metaDataListener.foundSubtitle(subtitle);
		}
	}
	
	private void reportNewState(MediaPlaybackState state) {
		if(stateListener != null) {
			stateListener.stateChanged(state);
		}
	}
	
	@Override
	public void
	dispose() 
	{
		disposed = true;
		
		doStop();
	}
}
