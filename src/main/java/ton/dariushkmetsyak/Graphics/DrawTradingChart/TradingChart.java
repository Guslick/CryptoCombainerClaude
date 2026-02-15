package ton.dariushkmetsyak.Graphics.DrawTradingChart;

import ExecPack.App;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYShapeAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.fx.ChartViewer;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYDotRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


public class TradingChart extends Application {
    private static TimeSeries series, markedPoints;
    private static ChartViewer chartViewer;
    private static IntervalMarker intervalMarker;
    private static XYPlot plot;
    public static JFreeChart chart;
    private static XYDotRenderer dotRenderer;
    static  {

        series = new TimeSeries("");
        markedPoints = new TimeSeries("");
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(series);
        dataset.addSeries(markedPoints);
        chart = ChartFactory.createTimeSeriesChart(
                "PriceChart",
                "Time",
                "Price",
                dataset,
                true,
                true,
                true);
        plot = chart.getXYPlot();


        // Рендерер для выделенных точек (без линий)
        dotRenderer = new XYDotRenderer();
        dotRenderer.setDotWidth(1);  // Размер точек
        dotRenderer.setDotHeight(1);
        dotRenderer.setSeriesPaint(1, Color.RED);  // Красный цвет для выделенных точек
        plot.setRenderer(1, dotRenderer);  // Применяем рендерер для второй серии (только точки)

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer();
        lineRenderer.setSeriesLinesVisible(1, false);  // Для первой серии линии видимы
        lineRenderer.setSeriesShapesVisible(0, false); // Для первой серии точки видимы
        lineRenderer.setSeriesPaint(0, Color.BLACK);  // Цвет для первой серии
        plot.setRenderer(0, lineRenderer);  // Применяем рендерер для первой серии

       // chartViewer = new ChartViewer(chart);

    }



    public static void addSimplePoint (double timestamp, double price){            //добавляем на график промежуточную точку (время, цена)
//        System.out.println("Timestamp: "+timestamp+ " Price: "+ price);
//        System.out.println(series.getMaximumItemAge());  // будем делать проверку на то что таймстамп последний, а не более ранний
        series.addOrUpdate(new Millisecond(Date.from(Instant.ofEpochMilli((long)timestamp))),price);

    }
    public static void addBuyIntervalMarker(double timestamp, double price) {            //добавляем на график точку покупки (время, цена)
        addSimplePoint(timestamp,price);
        intervalMarker = new IntervalMarker(timestamp,timestamp);
        intervalMarker.setPaint(Color.decode("#F0E68C"));
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(Color.GREEN);
        XYTextAnnotation annotation = new XYTextAnnotation(String.valueOf(price), timestamp, price);
        annotation.setPaint(Color.BLACK);
        annotation.setFont(new Font("Verdana", Font.BOLD, 20));
        plot.addAnnotation(annotation);
        valueMarker.setStroke(new BasicStroke(5));
        plot.addDomainMarker(valueMarker);
        plot.addDomainMarker(intervalMarker);
    }
    public static void addSimplePriceMarker(double timestamp, double price) {            //добавляем на график точку продажи (время, цена)
//        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
//      //  renderer.setSeriesLinesVisible(0, true);  // Показывать линии
//        renderer.setSeriesShapesVisible(0, true); // Показывать точки
//        // Устанавливаем красные точки на графике (выделяем красным)
//        renderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(0, 0, 2, 2)); // Устанавливаем форму маркера (круг)
//        renderer.setSeriesPaint(0, Color.BLACK);   // Цвет точек
      //  plot.setRenderer(renderer);

        markedPoints.addOrUpdate(new Millisecond(Date.from(Instant.ofEpochMilli((long)timestamp))),price);

//        Shape redPoint = new Ellipse2D.Double(timestamp , price , 3,3);
//        XYShapeAnnotation redPointAnnotation = new XYShapeAnnotation(redPoint,new BasicStroke(3,BasicStroke.CAP_BUTT, BasicStroke.CAP_BUTT),Color.RED, Color.RED);




        //addSimplePoint(timestamp,price);

        XYTextAnnotation annotation = new XYTextAnnotation("", timestamp, price);
        annotation.setPaint(Color.RED);
        annotation.setFont(new Font("Verdana", Font.BOLD, 15));
        annotation.setTextAnchor(TextAnchor.TOP_CENTER);
//        plot.addAnnotation(annotation);

        //plot.addAnnotation(redPointAnnotation);
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(Color.BLACK);
        valueMarker.setStroke(new BasicStroke(0));
        //plot.addDomainMarker(valueMarker);
       // intervalMarker=null;
    }
    public static void addSellIntervalMarker(double timestamp, double price) {            //добавляем на график точку продажи (время, цена)
        addSimplePoint(timestamp,price);
        intervalMarker.setEndValue(timestamp);
        XYTextAnnotation annotation = new XYTextAnnotation(String.valueOf(price), timestamp, price);
        annotation.setPaint(Color.BLACK);
        annotation.setFont(new Font("Verdana", Font.BOLD, 20));
        plot.addAnnotation(annotation);
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(Color.RED);
        valueMarker.setStroke(new BasicStroke(5));
        plot.addDomainMarker(valueMarker);
        intervalMarker=null;
    }

    public static void extendInBuyArea(double timestamp){                          // расширяем зону "В покупке"
        if (Optional.ofNullable(intervalMarker).isPresent()) {intervalMarker.setEndValue(timestamp);}
    }

    public static void makeScreenShot (String path){
        File file = new File(path);
        try {
            ChartUtils.saveChartAsPNG(file, chart, 1920, 1080);
        } catch (IOException e) {
            System.err.println("Не получилось сохранить скриншот графика на диск. Возможно нет доступа к диску или не достаточно свободного места");
            e.printStackTrace();
        }
    }
    public static void clearChart (){
        series.clear();
        markedPoints.clear();
        series = new TimeSeries("");
        markedPoints = new TimeSeries("");
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(series);
        dataset.addSeries(markedPoints);
        chart = ChartFactory.createTimeSeriesChart(
                "PriceChart",
                "Time",
                "Price",
                dataset,
                true,
                true,
                true);
        plot = chart.getXYPlot();


        // Рендерер для выделенных точек (без линий)
        dotRenderer = new XYDotRenderer();
        dotRenderer.setDotWidth(1);  // Размер точек
        dotRenderer.setDotHeight(1);
        dotRenderer.setSeriesPaint(1, Color.RED);  // Красный цвет для выделенных точек
        plot.setRenderer(1, dotRenderer);  // Применяем рендерер для второй серии (только точки)

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer();
        lineRenderer.setSeriesLinesVisible(1, false);  // Для первой серии линии видимы
        lineRenderer.setSeriesShapesVisible(0, false); // Для первой серии точки видимы
        lineRenderer.setSeriesPaint(0, Color.BLACK);  // Цвет для первой серии
        plot.setRenderer(0, lineRenderer);  // Применяем рендерер для первой серии

        //  chartViewer = new ChartViewer(chart);


    }

    public static void drawChart (Chart chart){
        TradingChart.chart.setTitle(chart.getCoinName());
        for (double[] priceTimestamp: chart.getPrices()){
            TradingChart.addSimplePoint(priceTimestamp[0],priceTimestamp[1]);
        }
    }
    public static void drawChart (ArrayList<double[]> chart, String chartTitle){
        TradingChart.chart.setTitle(chartTitle);
        for (double[] priceTimestamp: chart){
            TradingChart.addSimplePriceMarker(priceTimestamp[0],priceTimestamp[1]);
        }
    }
        @Override
    public void start(Stage stage) throws Exception {
        BorderPane root = new BorderPane();
        root.setCenter(chartViewer);
        Scene scene = new Scene(root, 1920, 1080);
        stage.setTitle("Title");
        stage.setScene(scene);
//        TimeUnit.SECONDS.sleep(10);
//        stage.show();
    }
}
