/*
 *
 * Copyright Inria, Bordeaux University.
 * Author : Jeremy Laviole. 
 *
 * No licence yet.
 */
package fr.inria.papart.depthcam.devices;

import static fr.inria.papart.depthcam.DepthAnalysis.papplet;
import fr.inria.papart.calibration.HomographyCalibration;
import fr.inria.papart.calibration.PlaneAndProjectionCalibration;
import fr.inria.papart.calibration.PlaneCalibration;
import fr.inria.papart.depthcam.PixelOffset;
import static fr.inria.papart.depthcam.DepthAnalysis.papplet;
import fr.inria.papart.procam.camera.CameraOpenKinect;
import java.util.ArrayList;
import java.util.Arrays;
import org.bytedeco.javacpp.indexer.Indexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.javacpp.opencv_core;
import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;
import org.bytedeco.javacpp.opencv_core.CvSize;
import org.bytedeco.javacpp.opencv_core.IplImage;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;
import toxi.geom.Vec3D;

/**
 *
 * @author jiii
 */
public class KinectProcessing extends KinectDepthAnalysis {

    public PImage validPointsPImage;
    IplImage nativeArrayToErode;
    UByteIndexer erosionIndexer;
    boolean[] validCopy;

    public KinectProcessing(PApplet parent, KinectDevice kinect) {
        super(parent, kinect);
        init();
    }

    public KinectProcessing(PApplet parent, KinectOne kinectOne) {
        super(parent, kinectOne);
        init();
    }

    private void init() {
        validPointsPImage = papplet.createImage(kinectDevice().depthWidth(), kinectDevice().depthHeight(), PConstants.RGB);
        nativeArrayToErode = IplImage.create(kinectDevice.depthWidth(), kinectDevice.depthHeight(), IPL_DEPTH_8U, 1);
        erosionIndexer = (UByteIndexer) nativeArrayToErode.createIndexer();
        validCopy = Arrays.copyOf(depthData.validPointsMask, depthData.validPointsMask.length);

    }

    public void updateMT(opencv_core.IplImage depth, opencv_core.IplImage color, PlaneAndProjectionCalibration calib, int skip2D) {
        updateRawDepth(depth);
        // optimisation no Color. 
        updateRawColor(color);
        depthData.clear();
        depthData.timeStamp = papplet.millis();
        depthData.planeAndProjectionCalibration = calib;
        computeDepthAndDo(skip2D, new Select2DPointPlaneProjection());

//        erodePoints(depthData.validPointsMask);
        erodePoints2(depthData.validPointsList, depthData.validPointsMask, skip2D);

        doForEachPoint(skip2D, new Select3DPointPlaneProjection());
//        erodePoints(depthData.validPointsMask3D);

        validPointsPImage.loadPixels();
        Arrays.fill(validPointsPImage.pixels, papplet.color(0, 0, 255));

        doForEachValidPoint(skip2D, new SetImageData());
        validPointsPImage.updatePixels();
    }

    private void erodePoints2(ArrayList<Integer> validList, boolean[] arrayToErode, int skip) {

        Arrays.fill(validCopy, false);
        
        for (Integer idx : validList) {
            PixelOffset po = PixelOffset.get(idx);
            int sum = 0;

            int x = po.x;
            int y = po.y;

            for (int j = y * kinectDevice.depthWidth() - skip;
                    j <= y * kinectDevice.depthWidth() + skip;
                    j += kinectDevice.depthWidth() * skip) {
                for (int i = x-skip; i <= x+skip; i += skip) {

                    int currentIdx = i + j;
                    sum += arrayToErode[currentIdx] ? 1 : 0;

                }
            }
            validCopy[idx] = sum >= 3;
        }

        System.arraycopy(validCopy, 0, arrayToErode, 0, arrayToErode.length);

//        erodePoints(depthData.validPointsMask);
    }

    private void erodePoints(boolean[] arrayToErode) {

        for (int i = 0; i < kinectDevice.depthWidth() * kinectDevice.depthHeight(); i++) {
            erosionIndexer.put(i, arrayToErode[i] ? 1 : 0);
        }
        cvErode(nativeArrayToErode, nativeArrayToErode);
        for (int i = 0; i < kinectDevice.depthWidth() * kinectDevice.depthHeight(); i++) {
            arrayToErode[i] = erosionIndexer.get(i) == 1;
        }

    }

    class ErodeValidPoints implements DepthPointManiplation {

        @Override
        public void execute(Vec3D p, PixelOffset px) {
            if (depthData.planeAndProjectionCalibration.hasGoodOrientationAndDistance(p)) {

//                Vec3D projected = depthData.planeAndProjectionCalibration.project(p);
//                depthData.projectedPoints[px.offset] = projected;
                depthData.planeAndProjectionCalibration.project(p, depthData.projectedPoints[px.offset]);

                if (isInside(depthData.projectedPoints[px.offset], 0.f, 1.f, 0.0f)) {
                    depthData.validPointsMask[px.offset] = true;
                    depthData.validPointsList.add(px.offset);
                }
            }
        }
    }

    public PImage update(IplImage depth, IplImage color,
            PlaneAndProjectionCalibration planeProjCalibration, int skip) {
        updateRawDepth(depth);
        updateRawColor(color);
        depthData.clear();
        depthData.timeStamp = papplet.millis();
        validPointsPImage.loadPixels();
        // set a default color. 
        Arrays.fill(validPointsPImage.pixels, papplet.color(0, 0, 255));

        depthData.planeAndProjectionCalibration = planeProjCalibration;
        computeDepthAndDo(skip, new Select2DPointPlaneProjection());
//        computeDepthAndDo(skip, new Select2DPointOverPlane());

        doForEachValidPoint(skip, new SetImageData());
        validPointsPImage.updatePixels();
        return validPointsPImage;
    }

    /**
     * Simple visualization
     *
     * @param depth
     * @param color
     * @param skip
     * @return
     */
    public PImage update(IplImage depth, IplImage color, int skip) {
        updateRawDepth(depth);
        updateRawColor(color);
        depthData.clear();
        depthData.timeStamp = papplet.millis();
        validPointsPImage.loadPixels();
        // set a default color. 

//                int offset =  kinectDevice.depthWidth() * 100 + 100;
//                    int r = (depthRaw[offset * 3 + 0] & 0xFF);
//            int g = (depthRaw[offset * 3 + 1] & 0xFF);
//            int b = (depthRaw[offset * 3 + 2] & 0xFF);
//            System.out.println(" r " + r + " g " + g + " b " + b);
//            float d =  (r - 127) + g * (1 << 8) + b * (1 << 16); 
//            System.out.println("value2 " + d);
//             d = d / (1 << 24) * 10000 + 400;
//            System.out.println("value1 " + d);
//        int high = depthRaw[offset * 3 + 1] & 0xFF;
//        int low= depthRaw[offset * 3] & 0xFF;
//        System.out.println("high " + high + " low " + low );
//        System.out.println("depth " + this.getDepth(offset) );
        Arrays.fill(validPointsPImage.pixels, papplet.color(0, 0, 0));

//        computeDepthAndDo(skip, new DoNothing());
        // TODO: get the color with Kinect2... 
        computeDepthAndDo(skip, new SetImageData());

//        computeDepthAndDo(skip, new Select2DPointOverPlane());
        validPointsPImage.updatePixels();
        return validPointsPImage;
    }

    class SetTouchInformation implements DepthPointManiplation {

        @Override
        public void execute(Vec3D p, PixelOffset px) {
//            depthData.validPointsMask[px.offset] = true;
            int r = depthData.touchAttributes[px.offset].isInTouch() ? 100 : 0;
            int g = depthData.touchAttributes[px.offset].isOverTouch() ? 100 : 0;
            int b = depthData.touchAttributes[px.offset].isUnderTouch() ? 100 : 0;
            setFakeColor(px.offset, r, g, b);
        }
    }

    class SetImageData implements DepthPointManiplation {

        @Override
        public void execute(Vec3D p, PixelOffset px) {
            depthData.validPointsMask[px.offset] = true;
            setPixelColor(px.offset);
        }
    }

    private void setFakeColor(int offset, int r, int g, int b) {
        int c = (r & 0xFF) << 16
                | (g & 0xFF) << 8
                | (b & 0xFF);
        validPointsPImage.pixels[offset] = c;
    }

    private void setPixelColor(int offset) {
        int colorOffset = kinectDevice.findColorOffset(depthData.depthPoints[offset]) * 3;
        int c = (colorRaw[colorOffset + 2] & 0xFF) << 16
                | (colorRaw[colorOffset + 1] & 0xFF) << 8
                | (colorRaw[colorOffset + 0] & 0xFF);

        validPointsPImage.pixels[offset] = c;
    }

    public PImage getColouredDepthImage() {
        return validPointsPImage;
    }

}
