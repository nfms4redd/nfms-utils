/*
 *  Copyright (C) 2007-2011 GeoSolutions S.A.S.
 *  http://www.geo-solutions.it
 */

package it.geosolutions.unredd.stats.impl;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.JAI;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;

import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.log4j.Logger;
import org.geotools.image.jai.Registry;
import org.geotools.resources.image.ImageUtilities;
import org.jaitools.imageutils.ROIGeometry;
import org.jaitools.media.jai.classifiedstats.ClassifiedStats;
import org.jaitools.media.jai.classifiedstats.ClassifiedStatsDescriptor;
import org.jaitools.media.jai.classifiedstats.ClassifiedStatsRIF;
import org.jaitools.media.jai.classifiedstats.Result;
import org.jaitools.numeric.Range;
import org.jaitools.numeric.Statistic;

import com.sun.media.jai.operator.ImageReadDescriptor;

import it.geosolutions.unredd.stats.RangeParser;

/**
 * A process computing classified statistics based on a raster data set and a set of raster classification layers
 * 
 * @author Emanuele Tajariol (etj at geo-solutions dot it)
 */
public class RasterClassifiedStatistics {
    
    private final static Logger LOGGER = Logger.getLogger(RasterClassifiedStatistics.class);
    
    /**
     * Region Of Interest on wich calculate the stats
     */
    private ROIGeometry roiGeom = null;

    /**
     * @param roiGeom a Region Of Interest used for restrict the zone where calculate stats
     */
    public void setRoiGeom(ROIGeometry roiGeom) {
        this.roiGeom = roiGeom;
    }

    static {
        try {
            Registry.registerRIF(JAI.getDefaultInstance(), new ClassifiedStatsDescriptor(),
                    new ClassifiedStatsRIF(), Registry.JAI_TOOLS_PRODUCT);

        } catch (Throwable e) {
            // swallow exception in case the op has already been registered.
        } finally{
            LOGGER.debug("Scaning for all plugins with ImageIO.scanForPlugins() static method...");
            ImageIO.scanForPlugins();
        }
    }
    
    /**
     * Calculate the stats using the ClassifiedStats provided by JAI-Tools
     * 
     * @param deferredMode
     * @param dataFile
     * @param classificationFiles raster files used as classification layers
     * @param requestedStats a list of stats
     * @return a Map that hold the stats results
     * @throws IOException
     */
    public Map<MultiKey, List<Result>> execute(boolean deferredMode, DataFile dataFile,
            List<DataFile> classificationFiles, List<Statistic> requestedStats) throws IOException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Computing stats " + requestedStats + " on " + dataFile
                    + " classifying by " + classificationFiles);
        }

        RenderedImage dataImage = null;
        RenderedImage statsOp = null;
        RenderedImage classifiers[] = null;
        Double classNoData[] = null;

        List<Range> dataNDR = null; // No Data Ranges for data image
        if (dataFile.getNoValue() != null) {
            Range nodata = new Range(dataFile.getNoValue(), true, dataFile.getNoValue(), true);
            dataNDR = new ArrayList<Range>();
            dataNDR.add(nodata);
        }

        try {
            dataImage = loadImage(deferredMode, dataFile.getFile());
            classifiers = new RenderedImage[classificationFiles.size()];
            List<Range> dataRanges = extractJAIRange(classificationFiles);
            classNoData = new Double[classificationFiles.size()];
            
            int i = 0;
            for (DataFile dfile : classificationFiles) {
                classifiers[i] = loadImage(deferredMode, dfile.getFile());
                classNoData[i] = dfile.getNoValue() == null ? Double.NaN : dfile.getNoValue();
                i++;
            }

            Statistic reqStatsArr[] = requestedStats.toArray(new Statistic[requestedStats.size()]);

            final ParameterBlockJAI pb = new ParameterBlockJAI("ClassifiedStats");
            pb.addSource(dataImage);
            pb.setParameter("classifiers", classifiers);
            pb.setParameter("stats", reqStatsArr);
            if(dataRanges != null && dataRanges.size() > 0){
                pb.setParameter("ranges", dataRanges);
                pb.setParameter("rangesType", Range.Type.INCLUDE);
                LOGGER.info("The ranges Included are:");
                for(Range r : dataRanges){
                    LOGGER.info(r.toString());
                }
            }
            else{
                LOGGER.info("No ranges are included, process all pixel values");
            }
            if (dataNDR != null) {
                pb.setParameter("noDataRanges", dataNDR);
            }
            if (roiGeom != null) {
                pb.setParameter("roi", roiGeom);
            }
            pb.setParameter("noDataClassifiers", classNoData);
            // TODO make it configurable
            pb.setParameter("bands", new Integer[] { 0 });

            statsOp = JAI.create("ClassifiedStats", pb);
            ClassifiedStats stats = (ClassifiedStats) ((RenderedOp) statsOp)
                    .getProperty(ClassifiedStatsDescriptor.CLASSIFIED_STATS_PROPERTY);

            List<Map<MultiKey, List<Result>>> fullResults = stats.results();
            Map<MultiKey, List<Result>> results = fullResults.get(0);

            if (LOGGER.isTraceEnabled()) {
                for (MultiKey key : results.keySet()) {
                    for (Result r : results.get(key)) {
                        LOGGER.trace("Key -> " + key + " stats -> " + r);
                    }
                }
            }
            return results;
        } finally {
            if (statsOp != null) {
                try {
                    ImageUtilities.disposePlanarImageChain(PlanarImage.wrapRenderedImage(statsOp));
                } catch (Exception e) {
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug(e.getLocalizedMessage(), e);
                }
            }

            // TODO can be removed ? we are doing recursive clean up with the call above!
            // SG: as long as the statistics operation is based on NullOpImage we cannot rely on
            // stats image sources to dispose the original images, but we need to do it ourselves.

            System.out.println("Classifiers " + classifiers);
            if (classifiers != null) {
                for (RenderedImage ri : classifiers) {
                    if (ri != null) {
                        try {
                            ImageUtilities.disposePlanarImageChain(PlanarImage
                                    .wrapRenderedImage(ri));
                        } catch (Exception e) {
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug(e.getLocalizedMessage(), e);
                        }
                    }
                }
            }

        }
    }
    
    public List<Range> extractJAIRange(List<DataFile> layers){
        List<RangeParser> rList = new LinkedList<RangeParser>();
        for(DataFile f : layers){
            if(f.getRanges() != null){
                rList.addAll(f.getRanges());
            }
        }
        List<Range> outList = new ArrayList<Range>();
        if(rList != null && !rList.isEmpty()){
            for(RangeParser rp : rList){
                Range r = new Range(rp.getLeftEndPoint(), true, rp.getRightEndPoint(), true);
                outList.add(r);
            }
        }    
        return outList;
    }

    /**
     * Loads a {@link RenderedImage} from the specified file with the specified mode.
     * 
     * @param mode, {@link ComputationMode} can be {@link ComputationMode#DEFERRED} or {@link ComputationMode#IMMEDIATE} as per the JAI meaning.
     * @param tiffFile the input {@link File} where to read from.
     * 
     * @return
     * @throws IOException in case the creation of the {@link ImageInputStream} or the Immediate read with Imageio fails.
     */
    public static RenderedImage loadImage(boolean deferred, File tiffFile) throws IOException {
        // checks
        if (!tiffFile.isFile() || !tiffFile.exists() || !tiffFile.canRead()) {
            throw new IllegalArgumentException("Unable to load image from file: "
                    + tiffFile.getAbsolutePath() + "\n" + "isFile=" + tiffFile.isFile() + "\n"
                    + "exists=" + tiffFile.exists() + "\n" + "canRead=" + tiffFile.canRead());
        }

        if (deferred) {
            final ImageInputStream inStream = ImageIO.createImageInputStream(tiffFile);
            if (inStream == null) {
                throw new IllegalArgumentException("Unable to create input stream from file: "
                        + tiffFile.getAbsolutePath() + "\n" + "isFile=" + tiffFile.isFile() + "\n"
                        + "exists=" + tiffFile.exists() + "\n" + "canRead=" + tiffFile.canRead());
            }
            return ImageReadDescriptor.create(inStream, Integer.valueOf(0), false, false, false,
                    null, null, null, null, null);
        } else {
            return ImageIO.read(tiffFile);

        }

    }

}
