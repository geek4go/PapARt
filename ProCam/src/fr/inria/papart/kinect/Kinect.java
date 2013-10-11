/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.inria.papart.kinect;

import com.googlecode.javacv.cpp.opencv_core;
import com.googlecode.javacv.cpp.opencv_core.CvSize;
import com.googlecode.javacv.cpp.opencv_core.IplImage;
import fr.inria.papart.procam.ProjectiveDeviceP;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;
import processing.core.PMatrix3D;
import processing.core.PVector;
import toxi.geom.Matrix4x4;
import toxi.geom.Vec3D;

/**
 *
 * @author jeremy
 */
// TODO: 
//  Change every Update Function, to use the Hardware calibration. 
public class Kinect {

    public static PApplet parent;
    public float closeThreshold = 300f, farThreshold = 1800f;
    private Vec3D[] kinectPoints;
    private int[] colorPoints;
    private boolean[] validPoints;
    private PImage validPointsPImage;
    private byte[] depthRaw;
    private byte[] colorRaw;
    private byte[] validPointsRaw;
    private IplImage validPointsIpl;
    private int id;
    private int currentSkip = 1;
    private ProjectiveDeviceP kinectCalibIR, kinectCalibRGB;
    static float[] depthLookUp = null;
    // Debug purposes
    public static byte[] connectedComponent;
    public static byte currentCompo = 1;

//  Kinect with the standard calibration
    // DEPRECATED  (already...)
    public Kinect(PApplet parent, String calib, int id) {
        Kinect.parent = parent;


        boolean useRGB = false;
        try {
            kinectCalibRGB = ProjectiveDeviceP.loadCameraDevice(calib, 0);
            kinectCalibIR = ProjectiveDeviceP.loadCameraDevice(calib, 1);
            useRGB = true;
        } catch (Exception e) {
            System.out.println("Use IR kinect calibration only.");
        }

        try {
            kinectCalibIR = ProjectiveDeviceP.loadCameraDevice(calib, 0);
            useRGB = false;
        } catch (Exception e) {
            System.err.println("Error loading IR Kinect Calibration: " + e);
            e.printStackTrace();
        }

        init(id);
    }

    public Kinect(PApplet parent, String calibIR, String calibRGB, int id) {
        Kinect.parent = parent;

        try {
            kinectCalibRGB = ProjectiveDeviceP.loadCameraDevice(calibRGB, 0);
            kinectCalibIR = ProjectiveDeviceP.loadCameraDevice(calibIR, 0);
        } catch (Exception e) {
            System.out.println("Kinect init exception." + e);
        }

        init(id);
    }

    // Kinect with advanced calibration 
    // Not ready yet
//    public Kinect(PApplet parent, int id, String calibrationFile) {
//        init(id);
//    }
    public int getCurrentSkip() {
        return currentSkip;
    }
    // Deprecated
    PMatrix3D translateCam = new PMatrix3D(1, 0, 0, 5,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1);

//    PMatrix3D translateCam = new PMatrix3D(1, 0, 0, 0,
//            0, 1, 0, 0,
//            0, 0, 1, 0,
//            0, 0, 0, 1);
    public int findColorOffset(Vec3D v) {
//        PVector vt = new PVector(v.x, v.y, v.z);
//        PVector vt2 = new PVector();
//        kinectCalibRGB.getExtrinsics().mult(vt, vt2);
//
////        return kinectCalibRGB.worldToPixel(new Vec3D(vt.x, vt.y, vt.z));
//        return kinectCalibRGB.worldToPixel(new Vec3D(vt2.x, vt2.y, vt2.z));

        PVector vt = new PVector(v.x, v.y, v.z);
        PVector vt2 = new PVector();

        translateCam.mult(vt, vt2);

//        return kinectCalibRGB.worldToPixel(new Vec3D(vt.x, vt.y, vt.z));
        return kinectCalibRGB.worldToPixel(new Vec3D(vt2.x, vt2.y, vt2.z));
    }

    // TODO: change registration here... 
    // TODO: Change init to smaller inits, called by Update functions.
    private void init(int id) {
        this.id = id;

        connectedComponent = new byte[kinectCalibIR.getSize()];

        // TODO: create them at first use !!
        kinectPoints = new Vec3D[kinectCalibIR.getSize()];
        validPoints = new boolean[kinectCalibIR.getSize()];

        colorRaw = new byte[kinectCalibIR.getSize() * 3];
        depthRaw = new byte[kinectCalibIR.getSize() * 2];

        // For Processing output
        colorPoints = new int[kinectCalibIR.getSize()];
        validPointsPImage = parent.createImage(kinectCalibIR.getWidth(), kinectCalibIR.getHeight(), PConstants.RGB);

        // For OpenCV Image output
        validPointsIpl = IplImage.create(new CvSize(kinectCalibIR.getWidth(), kinectCalibIR.getHeight()), opencv_core.IPL_DEPTH_8U, 3);
        validPointsRaw = new byte[kinectCalibIR.getWidth() * kinectCalibIR.getHeight() * 3];

        if (depthLookUp == null) {
            depthLookUp = new float[2048];
            for (int i = 0; i < depthLookUp.length; i++) {
                depthLookUp[i] = rawDepthToMeters(i);
            }
        }
    }

    public int getId() {
        return this.id;
    }

    public byte[] getColorBuffer() {
        return this.colorRaw;
    }

    // Deprecated
    public void undistortRGB(IplImage rgb, IplImage out) {
        kinectCalibRGB.getDevice().undistort(rgb, out);
    }

    // Deprecated
    public void undistortIR(IplImage ir, IplImage out) {
        kinectCalibIR.getDevice().undistort(ir, out);
    }

    public void update(IplImage depth, int skip) {
        this.currentSkip = skip;
        ByteBuffer depthBuff = depth.getByteBuffer();
        depthBuff.get(depthRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);

                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                boolean good = isGoodDepth(d);
                validPoints[offset] = good;

//                if (good) {
                kinectPoints[offset] = kinectCalibIR.pixelToWorld(x, y, d);
//                }
            }
        }
    }

    public PImage updateP(IplImage depth, IplImage color) {
        return updateP(depth, color, 1);
    }

    public PImage updateP(IplImage depth, IplImage color, int skip) {

        this.currentSkip = skip;

        ByteBuffer depthBuff = depth.getByteBuffer();
        ByteBuffer colorBuff = color.getByteBuffer();

        depthBuff.get(depthRaw);
        colorBuff.get(colorRaw);

        validPointsPImage.loadPixels();

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);

                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];
                boolean good = isGoodDepth(d);
                validPoints[offset] = good;

                validPointsPImage.pixels[offset] = parent.color(0, 0, 255);
                if (good) {


                    kinectPoints[offset] = kinectCalibIR.pixelToWorld(x, y, d);
                    colorPoints[offset] = this.findColorOffset(kinectPoints[offset]);
                    int colorOffset = colorPoints[offset] * 3;
                    int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
                            | (colorRaw[colorOffset + 1] & 0xFF) << 8
                            | (colorRaw[colorOffset + 0] & 0xFF);

                    validPointsPImage.pixels[offset] = c;

//                    int colorOffset = offset * 3;
//                    int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
//                            | (colorRaw[colorOffset + 1] & 0xFF) << 8
//                            | (colorRaw[colorOffset + 0] & 0xFF);
//
//                    validPointsPImage.pixels[offset] = c;
                }

            }
        }

        validPointsPImage.updatePixels();
        return validPointsPImage;
    }

    public PImage updateP(IplImage depth, IplImage color, KinectScreenCalibration calib) {
        return updateP(depth, color, 1, calib);
    }

    public PImage updateP(IplImage depth, IplImage color, int skip, KinectScreenCalibration calib) {

        this.currentSkip = skip;

        ByteBuffer depthBuff = depth.getByteBuffer();
        ByteBuffer colorBuff = color.getByteBuffer();

        depthBuff.get(depthRaw);
        colorBuff.get(colorRaw);

        validPointsPImage.loadPixels();

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);

                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];


                validPoints[offset] = false;
                validPointsPImage.pixels[offset] = parent.color(0, 0, 255);

                if (isGoodDepth(d)) {

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);
                    kinectPoints[offset] = p;
//                    colorPoints[offset] = this.findColorOffset(p);

                    if (calib.plane().hasGoodOrientationAndDistance(p)) {

                        if (isInside(calib.project(p), 0.f, 1.f, 0.1f)) {

                            kinectPoints[offset] = kinectCalibIR.pixelToWorld(x, y, d);
                            colorPoints[offset] = this.findColorOffset(kinectPoints[offset]);
                            int colorOffset = colorPoints[offset] * 3;
                            int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
                                    | (colorRaw[colorOffset + 1] & 0xFF) << 8
                                    | (colorRaw[colorOffset + 0] & 0xFF);

                            validPointsPImage.pixels[offset] = c;

//                            int colorOffset = offset * 3;
//                            int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
//                                    | (colorRaw[colorOffset + 1] & 0xFF) << 8
//                                    | (colorRaw[colorOffset + 0] & 0xFF);
//                            validPointsPImage.pixels[offset] = c;
                            validPoints[offset] = true;
                        }
                    }
                }

            }

        }

        validPointsPImage.updatePixels();
        return validPointsPImage;
    }

    public IplImage updateIpl(IplImage depth, IplImage color) {
        return updateIpl(depth, color, 1);
    }

    public IplImage updateIpl(IplImage depth, IplImage color, int skip) {

        this.currentSkip = skip;

        ByteBuffer depthBuff = depth.getByteBuffer();
        ByteBuffer colorBuff = color.getByteBuffer();

        ByteBuffer outputBuff = validPointsIpl.getByteBuffer();

        depthBuff.get(depthRaw);
        outputBuff.get(validPointsRaw);
        colorBuff.get(colorRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;
                int outputOffset = offset * 3;


                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);

                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                validPoints[offset] = false;
                validPointsRaw[outputOffset + 2] = 0;
                validPointsRaw[outputOffset + 1] = 0;
                validPointsRaw[outputOffset + 0] = 0;

                if (isGoodDepth(d)) {
                    validPoints[offset] = true;

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);
                    kinectPoints[offset] = p;

                    colorPoints[offset] = this.findColorOffset(p);
                    int colorOffset = colorPoints[offset] * 3;
                    
//                    int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
//                            | (colorRaw[colorOffset + 1] & 0xFF) << 8
//                            | (colorRaw[colorOffset + 0] & 0xFF);
//                    validPointsPImage.pixels[offset] = c;

                     validPointsRaw[outputOffset + 2] = colorRaw[colorOffset + 2];
                    validPointsRaw[outputOffset + 1] = colorRaw[colorOffset + 1];
                    validPointsRaw[outputOffset + 0] = colorRaw[colorOffset + 0];
                    
//                    int colorOffset = offset * 3;
//                    validPointsRaw[outputOffset + 2] = colorRaw[colorOffset + 2];
//                    validPointsRaw[outputOffset + 1] = colorRaw[colorOffset + 1];
//                    validPointsRaw[outputOffset + 0] = colorRaw[colorOffset + 0];
                }

            }
        }

        outputBuff = (ByteBuffer) outputBuff.rewind();
        outputBuff.put(validPointsRaw);

        return validPointsIpl;
    }

    public PImage updateProj(IplImage depth, IplImage color, KinectScreenCalibration calib, Vec3D[] projectedPoints, int skip) {

        this.currentSkip = skip;

        ByteBuffer depthBuff = depth.getByteBuffer();
        ByteBuffer colorBuff = color.getByteBuffer();

        depthBuff.get(depthRaw);
        colorBuff.get(colorRaw);

        validPointsPImage.loadPixels();

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);

                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                validPointsPImage.pixels[offset] = parent.color(0, 0, 255);
                validPoints[offset] = false;

                if (isGoodDepth(d)) {

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);
                    kinectPoints[offset] = p;
//                    colorPoints[offset] = this.findColorOffset(p);

                    if (calib.plane().hasGoodOrientationAndDistance(p)) {

                        Vec3D project = calib.project(p);
                        if (isInside(project, 0.f, 1.f, 0.0f)) {


                            kinectPoints[offset] = p;
                            validPoints[offset] = true;
                            // Projection
                            projectedPoints[offset] = project;

//                            int colorOffset = colorPoints[offset] * 3;
//                            int colorOffset = offset * 3;
//
//                            int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
//                                    | (colorRaw[colorOffset + 1] & 0xFF) << 8
//                                    | (colorRaw[colorOffset + 0] & 0xFF);
//
//                            validPointsPImage.pixels[offset] = c;


                            kinectPoints[offset] = kinectCalibIR.pixelToWorld(x, y, d);
                            colorPoints[offset] = this.findColorOffset(kinectPoints[offset]);
                            int colorOffset = colorPoints[offset] * 3;
                            int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
                                    | (colorRaw[colorOffset + 1] & 0xFF) << 8
                                    | (colorRaw[colorOffset + 0] & 0xFF);

                            validPointsPImage.pixels[offset] = c;



                        }
                    }
                }
            }
        }

        validPointsPImage.updatePixels();

        return validPointsPImage;
    }

    public void updateMT(IplImage depth, IplImage color, KinectScreenCalibration calib, Vec3D[] projectedPoints) {
        updateMT(depth, color, calib, projectedPoints, 1);
    }

    public ArrayList<Integer> updateMT(IplImage depth, IplImage color, KinectScreenCalibration calib, Vec3D[] projectedPoints, int skip) {

        this.currentSkip = skip;

        ArrayList<Integer> points = new ArrayList<Integer>();

        ByteBuffer outputBuff = validPointsIpl.getByteBuffer();
        ByteBuffer depthBuff = depth.getByteBuffer();
        ByteBuffer colorBuff = color.getByteBuffer();

        depthBuff.get(depthRaw);
        outputBuff.get(validPointsRaw);
        colorBuff.get(colorRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                int colorOutputOffset = offset * 3;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);
                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                validPoints[offset] = false;

                if (isGoodDepth(d)) {
                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);
                    kinectPoints[offset] = p;
//                    colorPoints[offset] = this.findColorOffset(p);


                    if (calib.plane().hasGoodOrientationAndDistance(p)) {

                        Vec3D project = calib.project(p);
                        if (isInside(project, 0.f, 1.f, 0.1f)) {


                            kinectPoints[offset] = p;
                            validPoints[offset] = true;
                            points.add(offset);
                            // Projection
                            projectedPoints[offset] = project;

//                            int colorOffset = offset * 3;
//
//                            validPointsRaw[colorOutputOffset + 2] = colorRaw[colorOffset + 2];
//                            validPointsRaw[colorOutputOffset + 1] = colorRaw[colorOffset + 1];
//                            validPointsRaw[colorOutputOffset + 0] = colorRaw[colorOffset + 0];


                            kinectPoints[offset] = kinectCalibIR.pixelToWorld(x, y, d);
                            colorPoints[offset] = this.findColorOffset(kinectPoints[offset]);
                            int colorOffset = colorPoints[offset] * 3;

                            validPointsRaw[colorOutputOffset + 2] = colorRaw[colorOffset + 2];
                            validPointsRaw[colorOutputOffset + 1] = colorRaw[colorOffset + 1];
                            validPointsRaw[colorOutputOffset + 0] = colorRaw[colorOffset + 0];


                        }
                    }
                }
            }

        }

        return points;
    }

    public ArrayList<Integer> updateMT(IplImage depth, KinectScreenCalibration calib, Vec3D[] projectedPoints, int skip) {

        this.currentSkip = skip;
        ArrayList<Integer> points = new ArrayList<Integer>();

        ByteBuffer outputBuff = validPointsIpl.getByteBuffer();
        ByteBuffer depthBuff = depth.getByteBuffer();

        depthBuff.get(depthRaw);
        outputBuff.get(validPointsRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);
                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                validPoints[offset] = false;

                if (isGoodDepth(d)) {

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);

                    if (calib.plane().hasGoodOrientationAndDistance(p)) {

                        Vec3D project = calib.project(p);
                        if (isInside(project, 0.f, 1.f, 0.0f)) {

                            kinectPoints[offset] = p;
                            validPoints[offset] = true;
                            points.add(offset);
                            // Projection
                            projectedPoints[offset] = project;
                        }
                    }
                }
            }

        }

        return points;
    }

    public ArrayList<Integer> updateMT3D(IplImage depth, KinectScreenCalibration calib, Vec3D[] projectedPoints, int skip) {

        this.currentSkip = skip;
        ArrayList<Integer> points = new ArrayList<Integer>();

        ByteBuffer outputBuff = validPointsIpl.getByteBuffer();
        ByteBuffer depthBuff = depth.getByteBuffer();

        depthBuff.get(depthRaw);
        outputBuff.get(validPointsRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);
                if (d >= 2047) {
                    validPoints[offset] = false;
                    break;
                }
                d = 1000 * depthLookUp[(int) d];

                validPoints[offset] = false;

                if (isGoodDepth(d)) {

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);

                    if (calib.plane().hasGoodOrientation(p)) {

                        Vec3D project = calib.project(p);
//                        if (isInside(project, 0.f, 1.f, 0.8f)) {
                        if (isInside(project, 0.f, 1.f, 0.2f)) {

                            kinectPoints[offset] = p;
                            validPoints[offset] = true;
                            points.add(offset);
                            // Projection
                            projectedPoints[offset] = project;
                        }
                    }
                }
            }

        }

        return points;
    }

    // TO IMPLEMENT
    public ArrayList<Integer> updateOptimized3D(IplImage depth, KinectScreenCalibration calib, Vec3D[] projectedPoints, int skip) {

        this.currentSkip = skip;
        ArrayList<Integer> points = new ArrayList<Integer>();

        ByteBuffer outputBuff = validPointsIpl.getByteBuffer();
        ByteBuffer depthBuff = depth.getByteBuffer();

        depthBuff.get(depthRaw);
        outputBuff.get(validPointsRaw);

        for (int y = 0; y < kinectCalibIR.getHeight(); y += skip) {
            for (int x = 0; x < kinectCalibIR.getWidth(); x += skip) {

                int offset = y * kinectCalibIR.getWidth() + x;

                float d = (depthRaw[offset * 2] & 0xFF) << 8
                        | (depthRaw[offset * 2 + 1] & 0xFF);
                d = 1000 * depthLookUp[(int) d];

                validPoints[offset] = false;

                if (isGoodDepth(d)) {

                    Vec3D p = kinectCalibIR.pixelToWorld(x, y, d);

                    if (calib.plane().hasGoodOrientation(p)) {

                        Vec3D project = calib.project(p);
//                        if (isInside(project, 0.f, 1.f, 0.8f)) {
                        if (isInside(project, 0.f, 1.f, 0.2f)) {

                            kinectPoints[offset] = p;
                            validPoints[offset] = true;
                            points.add(offset);
                            // Projection
                            projectedPoints[offset] = project;
                        }
                    }
                }
            }

        }
        return points;
    }

    public PImage getDepthColor() {
        return validPointsPImage;
    }

    public IplImage getDepthColorIpl() {
        return validPointsIpl;
    }

    public boolean[] getValidPoints() {
        return validPoints;
    }

    public Vec3D[] getDepthPoints() {
        return kinectPoints;
    }

//     public static float rawDepthToMeters(int depthValue) {
//        if (depthValue < 2047) {
//            return (float) (1.0 / ((float) (depthValue) * -0.0030711016f + 3.3309495161f));
//        }
//        return 0.0f;
//    }
//    public static float rawDepthToMeters(int depthValue) {
//        if (depthValue < 2047) {
//            return 0.1236f * (float) Math.tan((double) depthValue / 2842.5 + 1.1863);
//        }
//        return 0.0f;
//    }
    ////////////// WORKS WITH   DEPTH- REGISTERED - MM ////////
    public static float rawDepthToMeters(int depthValue) {
        if (depthValue < 2047) {
            return (float) depthValue / 1000f;
        }
        return 0.0f;
    }

    private boolean isGoodDepth(float rawDepth) {
        return (rawDepth >= closeThreshold && rawDepth < farThreshold);
    }

    public static boolean isInside(Vec3D v, float min, float max, float sideError) {
        return v.x > min - sideError && v.x < max + sideError && v.y < max + sideError && v.y > min - sideError;
    }
}
