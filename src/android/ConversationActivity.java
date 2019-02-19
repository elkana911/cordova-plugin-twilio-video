package com.ekreative.cordova.videoconversations;


import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import com.google.gson.JsonObject;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.twilio.video.CameraCapturer;
import com.twilio.video.LocalParticipant;
import com.twilio.video.Room;
import com.twilio.video.Video;
import com.twilio.video.VideoRenderer;
import com.twilio.video.TwilioException;
import com.twilio.video.AudioTrack;
import com.twilio.video.CameraCapturer.CameraSource;
import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.Room;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoView;

import com.twilio.video.RemoteVideoTrack;
import com.twilio.video.RemoteDataTrack;
import com.twilio.video.RemoteAudioTrack;
import com.twilio.video.RemoteAudioTrackPublication;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.RemoteDataTrackPublication;

import com.ekreative.cordova.videoconversations.FakeR;
	
import java.util.Collections;

public class ConversationActivity extends AppCompatActivity {
    private static FakeR fakeR;
	
    private static final int CAMERA_MIC_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "VideoActivity";

    /*
     * You must provide a Twilio Access Token to connect to the Video service
     */

    /*
     * Access token used to connect. This field will be set either from the console generated token
     * or the request to the token server.
     */
    private String accessToken;
    private String roomId;

    /*
     * A Room represents communication between a local participant and one or more participants.
     */
    private Room room;
    private LocalParticipant localParticipant;

    /*
     * A VideoView receives frames from a local or remote video track and renders them
     * to an associated view.
     */
    private VideoView primaryVideoView;
    private VideoView thumbnailVideoView;

    /*
     * Android application UI elements
     */
    private TextView videoStatusTextView;
    private TextView identityTextView;
    private CameraCapturer cameraCapturer;
    private LocalAudioTrack localAudioTrack;
    private LocalVideoTrack localVideoTrack;
    private FloatingActionButton connectActionFab;
    private FloatingActionButton disconnectActionFab;
    private FloatingActionButton switchCameraActionFab;
    private FloatingActionButton localVideoActionFab;
    private FloatingActionButton muteActionFab;
    private FloatingActionButton speakerActionFab;
    private android.support.v7.app.AlertDialog alertDialog;
    private AudioManager audioManager;
    private String participantIdentity;

    private int previousAudioMode;
    private boolean previousMicrophoneMute;
    private VideoRenderer localVideoView;
    private boolean disconnectedFromOnDestroy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
	fakeR = new FakeR(this);
	    
        setContentView(fakeR.getId("layout", "activity_video"));
	//setContentView(R.layout.activity_video);

        primaryVideoView = (VideoView) findViewById(fakeR.getId("id", "primary_video_view"));
        thumbnailVideoView = (VideoView) findViewById(fakeR.getId("id", "thumbnail_video_view"));
        videoStatusTextView = (TextView) findViewById(fakeR.getId("id", "video_status_textview"));
        identityTextView = (TextView) findViewById(fakeR.getId("id", "identity_textview"));

        connectActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "connect_action_fab"));
        disconnectActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "disconnect_action_fab"));
        switchCameraActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "switch_camera_action_fab"));
        localVideoActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "local_video_action_fab"));
        muteActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "mute_action_fab"));
        speakerActionFab = (FloatingActionButton) findViewById(fakeR.getId("id", "speaker_action_fab"));

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        /*
         * Needed for setting/abandoning audio focus during call
         */
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);

        Intent intent = getIntent();

        this.accessToken = intent.getStringExtra("token");
        this.roomId =   intent.getStringExtra("roomId");

        /*
         * Check camera and microphone permissions. Needed in Android M.
         */
        if (!checkPermissionForCameraAndMicrophone()) {
            requestPermissionForCameraAndMicrophone();
        } else {
            createAudioAndVideoTracks();
            connectToRoom(roomId);
        }

        /*
         * Set the initial state of the UI
         */
        intializeUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            boolean cameraAndMicPermissionGranted = true;

            for (int grantResult : grantResults) {
                cameraAndMicPermissionGranted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }

            if (cameraAndMicPermissionGranted) {
                createAudioAndVideoTracks();
                connectToRoom(roomId);
            } else {
		    
                Toast.makeText(this,
                        fakeR.getId("string", "permissions_needed"),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected  void onResume() {
        super.onResume();
        /*
         * If the local video track was released when the app was put in the background, recreate.
         */
        if (localVideoTrack == null && checkPermissionForCameraAndMicrophone() && cameraCapturer != null) {
            localVideoTrack = LocalVideoTrack.create(this, true, cameraCapturer);
            localVideoTrack.addRenderer(localVideoView);

            /*
             * If connected to a Room then share the local video track.
             */
            if (localParticipant != null) {
                localParticipant.publishTrack(localVideoTrack);
            }
        }
    }

    @Override
    protected void onPause() {
        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         */
        if (localVideoTrack != null) {
            /*
             * If this local video track is being shared in a Room, remove from local
             * participant before releasing the video track. Participants will be notified that
             * the track has been removed.
             */
            if (localParticipant != null) {
                localParticipant.unpublishTrack(localVideoTrack);
            }

            localVideoTrack.release();
            localVideoTrack = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        /*
         * Always disconnect from the room before leaving the Activity to
         * ensure any memory allocated to the Room resource is freed.
         */
        if (room != null && room.getState() != Room.State.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        /*
         * Release the local audio and video tracks ensuring any memory allocated to audio
         * or video is freed.
         */
        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }

        super.onDestroy();
    }

    private boolean checkPermissionForCameraAndMicrophone(){
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForCameraAndMicrophone(){
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this,
                        fakeR.getId("string", "permissions_needed"),
                        Toast.LENGTH_LONG).show();		
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    CAMERA_MIC_PERMISSION_REQUEST_CODE);
        }
    }

    private void createAudioAndVideoTracks() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(this, true);

        // Share your camera
        cameraCapturer = new CameraCapturer(this, CameraSource.FRONT_CAMERA);
        localVideoTrack = LocalVideoTrack.create(this, true, cameraCapturer);
        primaryVideoView.setMirror(true);
        localVideoTrack.addRenderer(primaryVideoView);
        localVideoView = primaryVideoView;
    }

    private void setAccessToken() {
        // OPTION 1- Generate an access token from the getting started portal
        // https://www.twilio.com/console/video/dev-tools/testing-tools

        // OPTION 2- Retrieve an access token from your own web app
        // retrieveAccessTokenfromServer();
    }

    private void connectToRoom(String roomName) {
        configureAudio(true);
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(accessToken)
                .roomName(roomName);

        /*
         * Add local audio track to connect options to share with participants.
         */
        if (localAudioTrack != null) {
            connectOptionsBuilder
                    .audioTracks(Collections.singletonList(localAudioTrack));
        }

        /*
         * Add local video track to connect options to share with participants.
         */
        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
        }
        room = Video.connect(this, connectOptionsBuilder.build(), roomListener());
        setDisconnectAction();

        
    }

    /*
     * The initial state when there is no active room.
     */
    private void intializeUI() {
        //disconnectActionFab.hide();
        //connectActionFab.setImageDrawable(ContextCompat.getDrawable(this,
        //        fakeR.getId("drawable", "R.drawable.ic_call_white_24px")));
        //connectActionFab.show();
        //connectActionFab.setOnClickListener(connectActionClickListener());

        switchCameraActionFab.show();
        switchCameraActionFab.setOnClickListener(switchCameraClickListener());

        localVideoActionFab.show();
        localVideoActionFab.setOnClickListener(localVideoClickListener());

        muteActionFab.show();
        muteActionFab.setOnClickListener(muteClickListener());

        speakerActionFab.show();
        speakerActionFab.setOnClickListener(speakerClickListener());
    }

    /*
     * The actions performed during disconnect.
     */
    private void setDisconnectAction() {
        connectActionFab.hide();

        disconnectActionFab.show();
        disconnectActionFab.setOnClickListener(disconnectClickListener());
    }

    /*
     * Creates an connect UI dialog
     */
    private void showConnectDialog() {
        EditText roomEditText = new EditText(this);
        alertDialog = Dialog.createConnectDialog(roomEditText,
                connectClickListener(roomEditText), cancelConnectDialogClickListener(), this);
        alertDialog.show();
    }

    /*
     * Called when participant joins the room
     */
    private void addParticipant(RemoteParticipant participant) {
        /*
         * This app only displays video for one additional participant per Room
         */
        if (thumbnailVideoView.getVisibility() == View.VISIBLE) {
            Snackbar.make(connectActionFab,
                    "Multiple participants are not currently support in this UI",
                    Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
            return;
        }
        participantIdentity = participant.getIdentity();
     //   videoStatusTextView.setText("Participant "+ participantIdentity + " joined");
        videoStatusTextView.setText("");

        /*
         * Add participant renderer
         */
        if (participant.getRemoteVideoTracks().size() > 0) {
           // addParticipantVideo(participant.getVideoTracks().get(0));
	    RemoteVideoTrackPublication remoteVideoTrackPublication =
                    participant.getRemoteVideoTracks().get(0);

            /*
             * Only render video tracks that are subscribed to
             */
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                addParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }	
		
        }

        /*
         * Start listening for participant events
         */
        participant.setListener(participantListener());
    }

    /*
     * Set primary view as renderer for participant video track
     */
    private void addParticipantVideo(VideoTrack videoTrack) {
        moveLocalVideoToThumbnailView();
        primaryVideoView.setMirror(false);
        videoTrack.addRenderer(primaryVideoView);
    }

    private void moveLocalVideoToThumbnailView() {
        if (thumbnailVideoView.getVisibility() == View.GONE) {
            thumbnailVideoView.setVisibility(View.VISIBLE);
            localVideoTrack.removeRenderer(primaryVideoView);
            localVideoTrack.addRenderer(thumbnailVideoView);
            localVideoView = thumbnailVideoView;
            thumbnailVideoView.setMirror(cameraCapturer.getCameraSource() ==
                    CameraSource.FRONT_CAMERA);
        }
    }

    /*
     * Called when participant leaves the room
     */
    private void removeParticipant(RemoteParticipant participant) {
     //   videoStatusTextView.setText("Participant "+participant.getIdentity()+ " left.");
        if (!participant.getIdentity().equals(participantIdentity)) {
            return;
        }
	    
	 /*
         * Remove remote participant renderer
         */
        if (!participant.getRemoteVideoTracks().isEmpty()) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    participant.getRemoteVideoTracks().get(0);

            /*
             * Remove video only if subscribed to participant track
             */
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                removeParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }    
        moveLocalVideoToPrimaryView();
    }

    private void removeParticipantVideo(VideoTrack videoTrack) {
        videoTrack.removeRenderer(primaryVideoView);
    }

    private void moveLocalVideoToPrimaryView() {
        if (thumbnailVideoView.getVisibility() == View.VISIBLE) {
            localVideoTrack.removeRenderer(thumbnailVideoView);
            thumbnailVideoView.setVisibility(View.GONE);
            localVideoTrack.addRenderer(primaryVideoView);
            localVideoView = primaryVideoView;
            primaryVideoView.setMirror(cameraCapturer.getCameraSource() ==
                    CameraSource.FRONT_CAMERA);
        }
    }

    /*
     * Room events listener
     */
    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                localParticipant = room.getLocalParticipant();
           //     videoStatusTextView.setText("Connected to " + room.getName());
                videoStatusTextView.setText("Waiting on other participant to join");
                setTitle(room.getName());

                for (RemoteParticipant participant : room.getRemoteParticipants()) {
                    addParticipant(participant);
                    break;
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                videoStatusTextView.setText("Failed to connect");
                configureAudio(false);
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                localParticipant = null;
                videoStatusTextView.setText("Disconnected from " + room.getName());
                ConversationActivity.this.room = null;
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy) {
                    configureAudio(false);
                    intializeUI();
                    moveLocalVideoToPrimaryView();
                }
            }

            @Override
            public void onParticipantConnected(Room room, RemoteParticipant participant) {
                addParticipant(participant);

            }

            @Override
            public void onParticipantDisconnected(Room room, RemoteParticipant participant) {
                videoStatusTextView.setText("Participant disconnected");
                removeParticipant(participant);
            }

            @Override
            public void onRecordingStarted(Room room) {
                /*
                 * Indicates when media shared to a Room is being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
                Log.d(TAG, "onRecordingStarted");
            }

            @Override
            public void onRecordingStopped(Room room) {
                /*
                 * Indicates when media shared to a Room is no longer being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
                Log.d(TAG, "onRecordingStopped");
            }
        };
    }

    private RemoteParticipant.Listener participantListener() {
        return new RemoteParticipant.Listener() {
            @Override
            public void onAudioTrackPublished(RemoteParticipant remoteParticipant,
                                              RemoteAudioTrackPublication remoteAudioTrackPublication) {
                //videoStatusTextView.setText("onAudioTrackPublished");
            }

            @Override
            public void onAudioTrackUnpublished(RemoteParticipant remoteParticipant,
                                                RemoteAudioTrackPublication remoteAudioTrackPublication) {
               // videoStatusTextView.setText("onAudioTrackUnpublished");
            }

            @Override
            public void onDataTrackPublished(RemoteParticipant remoteParticipant,
                                             RemoteDataTrackPublication remoteDataTrackPublication) {
                //videoStatusTextView.setText("onDataTrackPublished");
            }

            @Override
            public void onDataTrackUnpublished(RemoteParticipant remoteParticipant,
                                               RemoteDataTrackPublication remoteDataTrackPublication) {              
                //videoStatusTextView.setText("onDataTrackUnpublished");
            }

            @Override
            public void onVideoTrackPublished(RemoteParticipant remoteParticipant,
                                              RemoteVideoTrackPublication remoteVideoTrackPublication) {              
                //videoStatusTextView.setText("onVideoTrackPublished");
            }

            @Override
            public void onVideoTrackUnpublished(RemoteParticipant remoteParticipant,
                                                RemoteVideoTrackPublication remoteVideoTrackPublication) {           
                //videoStatusTextView.setText("onVideoTrackUnpublished");
            }

            @Override
            public void onAudioTrackSubscribed(RemoteParticipant remoteParticipant,
                                               RemoteAudioTrackPublication remoteAudioTrackPublication,
                                               RemoteAudioTrack remoteAudioTrack) {            
               // videoStatusTextView.setText("onAudioTrackSubscribed");
            }

            @Override
            public void onAudioTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                 RemoteAudioTrackPublication remoteAudioTrackPublication,
                                                 RemoteAudioTrack remoteAudioTrack) {
               
               // videoStatusTextView.setText("onAudioTrackUnsubscribed");
            }

            @Override
            public void onAudioTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                       RemoteAudioTrackPublication remoteAudioTrackPublication,
                                                       TwilioException twilioException) {
               
                //videoStatusTextView.setText("onAudioTrackSubscriptionFailed");
            }

            @Override
            public void onDataTrackSubscribed(RemoteParticipant remoteParticipant,
                                              RemoteDataTrackPublication remoteDataTrackPublication,
                                              RemoteDataTrack remoteDataTrack) {
               
               // videoStatusTextView.setText("onDataTrackSubscribed");
            }

            @Override
            public void onDataTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                RemoteDataTrackPublication remoteDataTrackPublication,
                                                RemoteDataTrack remoteDataTrack) {
              
              //  videoStatusTextView.setText("onDataTrackUnsubscribed");
            }

            @Override
            public void onDataTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                      RemoteDataTrackPublication remoteDataTrackPublication,
                                                      TwilioException twilioException) {
                
               // videoStatusTextView.setText("onDataTrackSubscriptionFailed");
            }

            @Override
            public void onVideoTrackSubscribed(RemoteParticipant remoteParticipant,
                                               RemoteVideoTrackPublication remoteVideoTrackPublication,
                                               RemoteVideoTrack remoteVideoTrack) {
              
              //  videoStatusTextView.setText("onVideoTrackSubscribed");
                addParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onVideoTrackUnsubscribed(RemoteParticipant remoteParticipant,
                                                 RemoteVideoTrackPublication remoteVideoTrackPublication,
                                                 RemoteVideoTrack remoteVideoTrack) {
              //  videoStatusTextView.setText("onVideoTrackUnsubscribed");
                removeParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onVideoTrackSubscriptionFailed(RemoteParticipant remoteParticipant,
                                                       RemoteVideoTrackPublication remoteVideoTrackPublication,
                                                       TwilioException twilioException) {
              
              //  videoStatusTextView.setText("onVideoTrackSubscriptionFailed");
              
            }

            @Override
            public void onAudioTrackEnabled(RemoteParticipant remoteParticipant,
                                            RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackDisabled(RemoteParticipant remoteParticipant,
                                             RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onVideoTrackEnabled(RemoteParticipant remoteParticipant,
                                            RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }

            @Override
            public void onVideoTrackDisabled(RemoteParticipant remoteParticipant,
                                             RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }
        };
    }
	
    private DialogInterface.OnClickListener connectClickListener(final EditText roomEditText) {
        return new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                /*
                 * Connect to room
                 */
                connectToRoom(roomEditText.getText().toString());
            }
        };
    }

    private View.OnClickListener disconnectClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * Disconnect from room
                 */
                if (room != null) {
                    room.disconnect();
					disconnectedFromOnDestroy = true;
                }
                //intializeUI();
                finish();
            }
        };
    }

    private View.OnClickListener connectActionClickListener() {
        return new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                showConnectDialog();
            }
        };
    }

    private DialogInterface.OnClickListener cancelConnectDialogClickListener() {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                intializeUI();
                alertDialog.dismiss();
            }
        };
    }

    private View.OnClickListener switchCameraClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cameraCapturer != null) {
                    CameraSource cameraSource = cameraCapturer.getCameraSource();
                    cameraCapturer.switchCamera();
                    if (thumbnailVideoView.getVisibility() == View.VISIBLE) {
                        thumbnailVideoView.setMirror(cameraSource == CameraSource.BACK_CAMERA);
                    } else {
                        primaryVideoView.setMirror(cameraSource == CameraSource.BACK_CAMERA);
                    }
                }
            }
        };
    }

    private View.OnClickListener localVideoClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * Enable/disable the local video track
                 */
                if (localVideoTrack != null) {
                    boolean enable = !localVideoTrack.isEnabled();
                    localVideoTrack.enable(enable);
                    int icon;
			
                    if (enable) {
			    
                        icon = fakeR.getId("drawable", "ic_videocam_green_24px");
                        switchCameraActionFab.show();
                    } else {
                        icon = fakeR.getId("drawable", "ic_videocam_off_red_24px");
                        switchCameraActionFab.hide();
                    }
                    localVideoActionFab.setImageDrawable(
                            ContextCompat.getDrawable(ConversationActivity.this, icon));
                }
            }
        };
    }

    private View.OnClickListener muteClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * Enable/disable the local audio track. The results of this operation are
                 * signaled to other Participants in the same Room. When an audio track is
                 * disabled, the audio is muted.
                 */
                if (localAudioTrack != null) {
                    boolean enable = !localAudioTrack.isEnabled();
                    localAudioTrack.enable(enable);
			
                    int icon = enable ?
                            fakeR.getId("drawable", "ic_mic_green_24px") : fakeR.getId("drawable", "ic_mic_off_red_24px");
                    muteActionFab.setImageDrawable(ContextCompat.getDrawable(
                            ConversationActivity.this, icon));
                }
            }
        };
    }

    private View.OnClickListener speakerClickListener(){
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioManager.isSpeakerphoneOn()) {
                    audioManager.setSpeakerphoneOn(false);
                    speakerActionFab.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(),
                            fakeR.getId("drawable", "ic_volume_down_white_24px")));
                } else {
                    audioManager.setSpeakerphoneOn(true);
                    speakerActionFab.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(),
                            fakeR.getId("drawable", "ic_volume_down_green_24px")));
                }
            }
        };
    }

    private void retrieveAccessTokenfromServer() {
        Ion.with(this)
                .load("http://localhost:8000/token.php")
                .asJsonObject()
                .setCallback(new FutureCallback<JsonObject>() {
                    @Override
                    public void onCompleted(Exception e, JsonObject result) {
                        if (e == null) {
                            ConversationActivity.this.accessToken = result.get("token").getAsString();
                        } else {
				
                            Toast.makeText(ConversationActivity.this,
                                    fakeR.getId("string", "error_retrieving_access_token"), Toast.LENGTH_LONG)
                                    .show();
                        }
                    }
                });
    }

    private void configureAudio(boolean enable) {
        if (enable) {
            previousAudioMode = audioManager.getMode();
            // Request audio focus before making any device switch.
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            /*
             * Always disable microphone mute during a WebRTC call.
             */
            previousMicrophoneMute = audioManager.isMicrophoneMute();
            audioManager.setMicrophoneMute(false);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setMicrophoneMute(previousMicrophoneMute);
        }
    }
}
