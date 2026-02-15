package ton.dariushkmetsyak.Util;

public class Prices {
    public static double round (double price){
//        System.out.println("Before" + price);
        if (price<0.01&&price!=0) {price = (double) Math.round(price * 1000000) /1000000;} else
        if (price>=0.01&&price<1) {price = (double) Math.round(price * 10000) /10000;} else
        if (price>=1&&price<10) {price = (double) Math.round(price * 1000) / 1000;} else
        if (price>=10&&price<100000) {price = (double) Math.round(price * 100) / 100;} else
        if (price>=100000)price = (double) Math.round(price);
//        System.out.println("After" + price);
        return price;
    }
}
