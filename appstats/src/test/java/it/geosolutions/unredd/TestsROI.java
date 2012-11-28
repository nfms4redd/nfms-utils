/*
 *  GeoBatch - Open Source geospatial batch processing system
 *  https://github.com/nfms4redd/nfms-geobatch
 *  Copyright (C) 2007-2012 GeoSolutions S.A.S.
 *  http://www.geo-solutions.it
 *
 *  GPLv3 + Classpath exception
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.geosolutions.unredd;

import it.geosolutions.unredd.stats.impl.StatsRunner;
import it.geosolutions.unredd.stats.model.config.StatisticConfiguration;
import it.geosolutions.unredd.stats.model.config.util.TokenResolver;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Scanner;
import java.util.logging.Logger;

import javax.media.jai.RenderedOp;
import javax.media.jai.operator.MosaicDescriptor;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import junit.framework.TestCase;

import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.JTS;
import org.geotools.test.TestData;
import org.jaitools.imageutils.ROIGeometry;
import org.junit.Test;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

/**
 * @author DamianoG
 *
 */
public class TestsROI extends TestCase{

    
    public static enum Tokens {
        FILEPATH,
        LAYERNAME,
        YEAR,
        MONTH
    }
    
    
    @Test
    public void testROI() throws ParseException, IOException, MismatchedDimensionException, TransformException, JAXBException{
        
        // get reference to a geotiff and wrap into geotiffReader
        File tif = TestData.file(this, "forest_mask.tif");
        GeoTiffReader gtr = new GeoTiffReader(tif);
        
        // get the world to grid matrix 
        MathTransform g2w = gtr.getOriginalGridToWorld(PixelInCell.CELL_CORNER); //TODO Corner or Center?
        MathTransform w2g = g2w.inverse();
        
        // Transform a wkt into a geometry
        WKTReader wktReader = new WKTReader();
        Geometry sourceGeom = wktReader.read("POLYGON ((21.3 0.7, 27 0.7, 26.9 -6, 21.7 -6, 21.3 0.7))");
        
        // Translate the geometry from Model space to Raster space
        Geometry targetGeom = JTS.transform(sourceGeom, w2g);
        Logger.getAnonymousLogger().info(targetGeom.toText());
        
        gtr.getOriginalGridRange();
        
        RenderedImage [] imgArr = new RenderedImage[] {gtr.read(null).getRenderedImage()};
        ROIGeometry [] rgeomArr = new ROIGeometry[] {new ROIGeometry(targetGeom)};
        
        RenderedOp ro = MosaicDescriptor.create(imgArr, MosaicDescriptor.MOSAIC_TYPE_BLEND,null, rgeomArr, null, new double[]{0}, null);
        
        
        JAXBContext context= null;
        Unmarshaller unmarshaller = null;
        context = JAXBContext.newInstance(StatisticConfiguration.class);
        unmarshaller = context.createUnmarshaller();

        File f = TestData.file(this, "bigStat.xml");
        
        String output = new Scanner(f).useDelimiter("\\Z").next();
        StatisticConfiguration cfg = (StatisticConfiguration) unmarshaller.unmarshal(new StringReader(output));

        TokenResolver<StatisticConfiguration, Tokens> resolver = new TokenResolver(cfg, StatisticConfiguration.class);

//        resolver.putAll(layerUpdateProperties);
        
        cfg = (StatisticConfiguration)resolver.resolve();

        // initialize the file when saving statistics
        
//        cfg.getOutput().setFile(destFileName);
        StatsRunner runner = new StatsRunner(cfg, rgeomArr[0]);
        runner.run();
        
        
//        File tmp = TestData.temp(this, "saved.png");
//        ImageIO.write(imgArr[0], "png", tmp);
//        
//        
//        BufferedImage bi = ro.getAsBufferedImage();
//        
//        File tmp2 = TestData.temp(this, "saved2.png");
//        ImageIO.write(bi, "png", tmp2);
        
    }
    
    
    
}
