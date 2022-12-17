package dev.slimevr.osc;

import com.illposed.osc.*;
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector;
import com.illposed.osc.transport.OSCPortIn;
import com.illposed.osc.transport.OSCPortOut;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import dev.slimevr.VRServer;
import dev.slimevr.config.OSCConfig;
import dev.slimevr.vr.processor.HumanPoseProcessor;
import dev.slimevr.vr.processor.skeleton.Skeleton;
import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;

import java.io.IOException;
import java.net.InetAddress;


/**
 * VMC documentation: https://protocol.vmc.info/english
 */
public class VMCHandler implements OSCHandler {
	private OSCPortIn oscReceiver;
	private OSCPortOut oscSender;
	private OSCMessage oscMessage;
	private final OSCConfig config;
	private final VRServer server;
	private final HumanPoseProcessor humanPoseProcessor;
	private final FastList<Object> oscArgs = new FastList<>(3);
	private final Vector3f vecBuf = new Vector3f();
	private final Quaternion quatBuf = new Quaternion();
	private float timeAtLastError;
	private int lastPortIn;
	private int lastPortOut;
	private InetAddress lastAddress;

	public VMCHandler(
		VRServer server,
		HumanPoseProcessor humanPoseProcessor,
		OSCConfig oscConfig
	) {
		this.server = server;
		this.humanPoseProcessor = humanPoseProcessor;
		this.config = oscConfig;

		refreshSettings(false);
	}

	@Override
	public void refreshSettings(boolean refreshRouterSettings) {
		// Stops listening and closes OSC port
		boolean wasListening = oscReceiver != null && oscReceiver.isListening();
		if (wasListening) {
			oscReceiver.stopListening();
		}
		boolean wasConnected = oscSender != null && oscSender.isConnected();
		if (wasConnected) {
			try {
				oscSender.close();
			} catch (IOException e) {
				LogManager.severe("[VMCHandler] Error closing the OSC sender: " + e);
			}
		}

		if (config.getEnabled()) {
			// Instantiates the OSC receiver
			try {
				int port = config.getPortIn();
				oscReceiver = new OSCPortIn(
					port
				);
				if (lastPortIn != port || !wasListening) {
					LogManager.info("[VMCHandler] Listening to port " + port);
				}
				lastPortIn = port;
			} catch (IOException e) {
				LogManager
					.severe(
						"[VMCHandler] Error listening to the port "
							+ config.getPortIn()
							+ ": "
							+ e
					);
			}

			// Starts listening for VMC messages
			if (oscReceiver != null) {
				OSCMessageListener listener = this::handleReceivedMessage;
				MessageSelector selector = new OSCPatternAddressMessageSelector(
					"/VMC/*"
				);
				oscReceiver.getDispatcher().addListener(selector, listener);
				oscReceiver.startListening();
			}

			// Instantiate the OSC sender
			try {
				InetAddress address = InetAddress.getByName(config.getAddress());
				int port = config.getPortOut();
				oscSender = new OSCPortOut(
					address,
					port
				);
				if ((lastPortOut != port && lastAddress != address) || !wasConnected) {
					LogManager
						.info(
							"[VMCHandler] Sending to port "
								+ port
								+ " at address "
								+ address.toString()
						);
				}
				lastPortOut = port;
				lastAddress = address;

				oscSender.connect();
			} catch (IOException e) {
				LogManager
					.severe(
						"[VMCHandler] Error connecting to port "
							+ config.getPortOut()
							+ " at the address "
							+ config.getAddress()
							+ ": "
							+ e
					);
			}
		}

		if (refreshRouterSettings && server.getOSCRouter() != null)
			server.getOSCRouter().refreshSettings(false);
	}

	void handleReceivedMessage(OSCMessageEvent event) {
		// TODO ?
	}

	@Override
	public void update() {
		// Send OSC data
		if (oscSender != null && oscSender.isConnected()) {
			// Send our time (used to check if communication is possible)
			oscArgs.clear();
			oscArgs.add((float) System.currentTimeMillis());
			oscMessage = new OSCMessage("/VMC/Ext/T", oscArgs);
			try {
				oscSender.send(oscMessage);
			} catch (IOException | OSCSerializeException e) {
				// Avoid spamming AsynchronousCloseException too many
				// times per second
				if (System.currentTimeMillis() - timeAtLastError > 100) {
					timeAtLastError = System.currentTimeMillis();
					LogManager
						.warning(
							"[VMCHandler] Error sending OSC message: "
								+ e
						);
				}
			}

			Skeleton skeleton = humanPoseProcessor.getSkeleton();
			if (skeleton != null) {
				// TODO: is needed?
				// Send root transform
				humanPoseProcessor.getSkeleton().getRootNode().worldTransform
					.getTranslation(vecBuf);
				humanPoseProcessor.getSkeleton().getRootNode().worldTransform.getRotation(quatBuf);
				oscArgs.clear();
				oscArgs.add("root");
				oscArgs.add(vecBuf.x);
				oscArgs.add(vecBuf.y);
				oscArgs.add(vecBuf.z);
				oscArgs.add(quatBuf.getX());
				oscArgs.add(quatBuf.getY());
				oscArgs.add(quatBuf.getZ());
				oscArgs.add(quatBuf.getW());
				oscMessage = new OSCMessage(
					"/VMC/Ext/Root/Pos",
					oscArgs
				);
				try {
					oscSender.send(oscMessage);
				} catch (IOException | OSCSerializeException e) {
					// Avoid spamming AsynchronousCloseException too many
					// times per second
					if (System.currentTimeMillis() - timeAtLastError > 100) {
						timeAtLastError = System.currentTimeMillis();
						LogManager
							.warning(
								"[VMCHandler] Error sending OSC message: "
									+ e
							);
					}
				}

				// Send humanoid bones transforms
				// TODO foreach
				vecBuf.set(0f, 1f, 2f);
				quatBuf.set(Quaternion.Y_90_DEG);
				oscArgs.clear();
				// https://docs.unity3d.com/ScriptReference/HumanBodyBones.html
				oscArgs.add("RightLowerArm");
				oscArgs.add(vecBuf.x);
				oscArgs.add(vecBuf.y);
				oscArgs.add(vecBuf.z);
				oscArgs.add(quatBuf.getX());
				oscArgs.add(quatBuf.getY());
				oscArgs.add(quatBuf.getZ());
				oscArgs.add(quatBuf.getW());
				oscMessage = new OSCMessage(
					"/VMC/Ext/Bone/Pos",
					oscArgs
				);
				try {
					oscSender.send(oscMessage);
				} catch (IOException | OSCSerializeException e) {
					// Avoid spamming AsynchronousCloseException too many
					// times per second
					if (System.currentTimeMillis() - timeAtLastError > 100) {
						timeAtLastError = System.currentTimeMillis();
						LogManager
							.warning(
								"[VMCHandler] Error sending OSC message: "
									+ e
							);
					}
				}
			}
		}
	}

	@Override
	public OSCPortOut getOscSender() {
		return oscSender;
	}

	@Override
	public int getPortOut() {
		return lastPortOut;
	}

	@Override
	public InetAddress getAddress() {
		return lastAddress;
	}

	@Override
	public OSCPortIn getOscReceiver() {
		return oscReceiver;
	}

	@Override
	public int getPortIn() {
		return lastPortIn;
	}
}
