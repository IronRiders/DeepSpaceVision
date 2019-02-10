/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.naming.directory.NoSuchAttributeException;

import java.lang.Math;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.*;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Example pipeline.
   */
 /* public static class MyPipeline implements VisionPipeline {
    public int val;

    @Override
    public void process(Mat mat) {
      val += 1;
    }
  }
  */

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }
     NetworkTable table = ntinst.getTable("GRIP");

    // start cameras
    List<VideoSource> cameras = new ArrayList<>();
    for (CameraConfig cameraConfig : cameraConfigs) {
      cameras.add(startCamera(cameraConfig));
    }

  // Constants for Distance to robot calculations
  final int TAPE_ANGLE = 14;
  final double CAMERA_VIEW_ANGLE = 78;
  final double LENGTH_OF_BOUNDING_RECTANGLE_INCHES = 2 * Math.sin(180-90-TAPE_ANGLE) + 5.5 * Math.sin(14);
  final double HEIGHT_OF_BOUNDING_RECTANGLE_INCHES = 5.5 * Math.sin(TAPE_ANGLE) + 2 * Math.cos(180 - 90 - TAPE_ANGLE);
  final double distanceBetweenTapeCentersInches = LENGTH_OF_BOUNDING_RECTANGLE_INCHES + 8; // 2 * (Width of bounding square) (times 2 squares / half their width) / 2 + distance between top inner tips
  
  // Creating networktables and getting their entrees
  NetworkTableInstance piOutpuTableInstance = NetworkTableInstance.create();
  NetworkTable piOutpuTable = piOutpuTableInstance.getTable("PI_Output");
  NetworkTableEntry distanceToRobotEntry = piOutpuTable.getEntry("DistanceToRobotInches");
  NetworkTableEntry distanceRightToRobotEntry = piOutpuTable.getEntry("DistanceRightToRobot");
  NetworkTableEntry angleOfRobotToTape = piOutpuTable.getEntry("AngleOfRobotToTape"); // Not Implemented

  // Camera Resolution: 1080p
  final int HEIGHT_OF_CAMERA_PIXELS = 1080;
  final int WIDTH_OF_CAMERA_PIXELS = 1920;
  

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> { // the constructor in the wpi says (camera, visionpipeline, this - Iterative robot)
                if (!pipeline.filterContoursOutput().isEmpty()) {
                  Rect contour1 = Imgproc.boundingRect(pipeline.filterContoursOutput().get(0));
                  Rect contour2 = Imgproc.boundingRect(pipeline.filterContoursOutput().get(1));
                  synchronized (contour1) {
                      double inchesPerPixel, 
                              newAngle,
                              distanceBetweenTapeCentersPixels, 
                              distanceToRobotInches, 
                              tapeDistanceRightInches,
                              distanceToRobotBasedOnTapeHeight;
                      int tapeCenterPixelsToCenterScreen;
                      int centerX1 = contour1.x + (contour1.width / 2);
                      int centerX2 = contour2.x + (contour2.width / 2);
                      int heightOfTapePixels = (contour1.height + contour2.height) / 2;


                      // Not Needed because x1 and x2 are found above
                      // int contour1X, contour2X;
                      // NetworkTableEntry contour1XTableValue = table.getEntry("contour1X"); // Needs to be changed to match real networktable key
                      // NetworkTableEntry contour2XTableValue = table.getEntry("contour2X");
                      // contour1X = (int) contour1XTableValue.getNumber(0);
                      // contour2X = (int) contour2XTableValue.getNumber(0);

                      // Calculate the distance between the robot and the tape.
                      distanceBetweenTapeCentersPixels = centerX2 - centerX1;
                      tapeCenterPixelsToCenterScreen = (centerX2 + centerX1) / 2 - WIDTH_OF_CAMERA_PIXELS; // finds how far right
                                                                                                      // the tapes are from
                                                                                                      // the center of the
                                                                                                      // screen in pixels
                      inchesPerPixel = distanceBetweenTapeCentersInches / distanceBetweenTapeCentersPixels ;
                      newAngle = distanceBetweenTapeCentersPixels/WIDTH_OF_CAMERA_PIXELS * CAMERA_VIEW_ANGLE/2; // half of cone of vision is 39 degrees

                      
                      // these values will be used to determing the path of the robot
                      distanceToRobotInches = (distanceBetweenTapeCentersInches / 2) / Math.tan(newAngle);
                      tapeDistanceRightInches = tapeCenterPixelsToCenterScreen * inchesPerPixel;
                      distanceToRobotBasedOnTapeHeight = (heightOfTapePixels / 2 * inchesPerPixel) / Math.tan(CAMERA_VIEW_ANGLE / 2 * heightOfTapePixels / HEIGHT_OF_CAMERA_PIXELS);

                      // Output values to NetworkTables if two calculated values are within X percent
                      if(Math.abs(distanceToRobotBasedOnTapeHeight - distanceToRobotInches)/ distanceToRobotInches < 0.10) {
                        distanceToRobotEntry.setDouble(distanceToRobotInches);
                        distanceRightToRobotEntry.setDouble(tapeDistanceRightInches);
                        angleOfRobotToTape.setDouble(0); // dummy value
                      }
                      else {
                        distanceToRobotEntry.setDouble(-1); // Impossible value
                        distanceRightToRobotEntry.setDouble(0); // dummy value
                        angleOfRobotToTape.setDouble(360); // dummy value
                      }
                      

                  }
                }     
      });
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
