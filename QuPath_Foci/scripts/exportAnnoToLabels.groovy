/*
// ABOUT
Exports Annotations for StarDist (Or other Deep Learning frameworks)

// INPUTS
You need rectangular annotations that have classes "Training" and "Validation"
After you have placed these annotations, lock them and start drawing the objects inside

// OUTPUTS
----------
The script will export each annotation and whatever is contained within as an image-label pair
These will be placed in the folder specified by the user in the main project directory.
Inside that directory, you will find 'train' and 'test' directories that contain the images with
class 'Training' and 'Validation', respectively.
Inside each, you will find 'images' and 'masks' folders containing the exported image and the labels,
respectively. The naming convention was chosen to match the one used for the StarDist DSBdataset

//PARAMETERS
------------
- channel_of_interest: You can export a single channel or all of them, currently no option for _some_channels only
- downsample: you can downsample your image in case it does not make sense for you to train on the full resolution
- export_directory: name of the directory which will contain the 'train' and 'test' subdirectories

Authors: Olivier Burri, Romain Guiet BioImaging and Optics Platform (EPFL BIOP)

Tested on QuPath 0.2.0-m11, May 6th 2020

Due to the simple nature of this code, no copyright is applicable
Adapted by Philippe Mailly to import rois from ImageJ
*/


// Manage Imports
// Manage Imports
import qupath.imagej.gui.IJExtension
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.viewer.QuPathViewerPlus
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.writers.TileExporter
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RectangleROI
import qupath.lib.scripting.QP
import qupathj.*
import ij.IJ
import ij.gui.Roi
import ij.plugin.ChannelSplitter
import ij.plugin.frame.RoiManager
import org.apache.commons.io.FilenameUtils
import java.awt.image.BufferedImage
import static qupath.imagej.tools.IJTools.convertToImagePlus
import static qupath.lib.gui.scripting.QPEx.getCurrentViewer
import static qupath.lib.scripting.QP.*


// USER SETTINGS
def channel_of_interest = 1 // null to export all the channels
def downsample = 1
def imageDir = new File(project.getImageList()[0].getURIs()[0]).getParent()
def spacing = 512

// START OF SCRIPT
clearAllObjects()

// draw tiles training Validation
drawTilesAnnotation(spacing)

// load rois
loadRois(imageDir)


def training_regions = getAnnotationObjects().findAll { it.getPathClass() == getPathClass("Training") }
if (training_regions.size() > 0 ) saveRegions( imageDir, training_regions, channel_of_interest, downsample, 'train')
def validation_regions = getAnnotationObjects().findAll { it.getPathClass() == getPathClass("Validation") }
if (validation_regions.size() > 0 ) saveRegions(  imageDir, validation_regions, channel_of_interest, 'test')


def saveRegions( def imageDir,def regions, def channel, def downsample, def type ) {
    // Randomize names
    def is_randomized = getProject().getMaskImageNames()
    getProject().setMaskImageNames(true)
    def rm = RoiManager.getRoiManager() ?: new RoiManager()
    // Get the image name
    def image_name = getProjectEntry().getImageName()
    regions.eachWithIndex{ region, region_idx ->
        println('Processing Region #'+(  region_idx + 1 ) )

        def file_name =  image_name+"_r"+( region_idx + 1 )
        def imageData = getCurrentImageData()
        def server = imageData.getServer()
        def viewer = getCurrentViewer()
        def hierarchy = getCurrentHierarchy()

        //def image = GUIUtils.getImagePlus( region, downsample, false, true )
        request = RegionRequest.createInstance(imageData.getServerPath(), downsample, region.getROI())
        def pathImage = IJExtension.extractROIWithOverlay(server, region, hierarchy, request, false, viewer.getOverlayOptions());
        image = pathImage.getImage()
        println("Image received" )
        //image.show()
        // Create the Labels image
        def labels = IJ.createImage( "Labels", "16-bit black", image.getWidth(), image.getHeight() ,1 );
        rm.reset()

        IJ.run(image, "To ROI Manager", "")

        def rois = rm.getRoisAsArray() as List
        println("Creating Labels" )

        def label_ip = labels.getProcessor()
        def idx = 0
        rois.each{ roi ->
            if (roi.getType() == Roi.RECTANGLE) {
                println("Ignoring Rectangle")
            } else {
                label_ip.setColor( ++idx )
                label_ip.setRoi( roi )
                label_ip.fill( roi )


            }
        }
        labels.setProcessor( label_ip )

        //labels.show()

        // Split to keep only channel of interest
        def output = image
        if  ( channel != null){
            imp_chs =  ChannelSplitter.split( image )
            output = imp_chs[  channel - 1 ]
        }

        saveImages(imageDir, output, labels, file_name, type)
        println( file_name + " Image and Mask Saved." )

        // Save some RAM
        output.close()
        labels.close()
        image.close()
    }

    // Return Project setup as it was before
    getProject().setMaskImageNames( is_randomized )
}

def loadRois(imageDir) {
    def server = getCurrentImageData().getServer()
    def imageName = getProjectEntry().getImageName()
    def imgNameWithOutExt = FilenameUtils.removeExtension(imageName)
    def rois_path = buildFilePath(new File(imageDir).getParent(), 'Rois')
    def imageRoi = buildFilePath(rois_path, imgNameWithOutExt+'.zip')
    def rm = RoiManager.getRoiManager() ?: new RoiManager(false)
    rm.runCommand("Open", imageRoi)
    def rois = rm.getRoisAsArray() as List
    def request = RegionRequest.createInstance(server, 1)
    def img = convertToImagePlus(server, request)
    def QuPathViewerPlus viewer =  QuPathGUI.getInstance().getViewer()
    List<PathObject> pathObjects =QuPath_Send_Overlay_to_QuPath.createObjectsFromROIs(img.getImage(), rois, 1, false, false, viewer.getImagePlane())
    PathObjectHierarchy hierarchy = getCurrentImageData().getHierarchy();
    hierarchy.addObjects(pathObjects)
}

def drawTilesAnnotation(spacing) {
    // see training/validation (60/40)
    def imageData = getCurrentImageData()
    def server = imageData.getServer()
    for (int y = 0; y < server.getHeight(); y += spacing) {
        int h = spacing
        if (y + h > server.getHeight())
            h = server.getHeight() - y
        for (int x = 0; x < server.getWidth(); x += spacing) {
            int w = spacing
            if (x + w > server.getWidth())
                w = server.getWidth() - x
            def roi = new RectangleROI(x, y, w, h)
            def tile = new PathAnnotationObject(roi, PathClass.fromString("Training"))
            imageData.getHierarchy().addObject(tile)
        }
    }
}


// This will save the images in the selected folder
def saveImages(def imageDir, def images, def labels, def name, def type) {
    def source_folder = new File ( buildFilePath( imageDir, 'ground_truth', type, 'images' ) )
    def target_folder = new File ( buildFilePath( imageDir, 'ground_truth', type, 'masks' ) )
    mkdirs( source_folder.getAbsolutePath() )
    mkdirs( target_folder.getAbsolutePath() )

    IJ.save( images , new File ( source_folder, name ).getAbsolutePath()+'.tif' )
    IJ.save( labels , new File ( target_folder, name ).getAbsolutePath()+'.tif' )

}


print "done"