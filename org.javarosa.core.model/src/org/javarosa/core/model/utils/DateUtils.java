package org.javarosa.core.model.utils;

import java.util.Calendar;
import java.util.Date;
import java.util.Vector;

/**
 * Static utility methods for Dates in j2me 
 * 
 * @author Clayton Sims
 *
 */
public class DateUtils {

	public DateUtils() {
		super();
		// TODO Auto-generated constructor stub
	}

	/**
	 * Converts the date into a string formatted according to the xsd:dateTime specifications
	 * @param date The date to be converted
	 * @return The date represented by 'date' in the XML dateTime format.
	 */
	public static String formatDateToTimeStamp(Date date) {
		String dateElement = getXMLStringValue(date);
		
		Calendar cd = Calendar.getInstance();
		cd.setTime(date);
		String hour = String.valueOf(cd.get(Calendar.HOUR));
		hour = hour.length() < 2 ? "0" + hour : hour;
		String minute = String.valueOf(cd.get(Calendar.MINUTE));
		minute = minute.length() < 2 ? "0" + minute : minute;
		String time = hour + ":"+ minute + ":" + String.valueOf(cd.get(Calendar.MILLISECOND)).substring(0,2);
		
		return dateElement + "T" + time;
	}

	/**
	 * Converts the value object into a String based on the returnType
	 * Note: This is for a short
	 *
	 * @return
	 */
	public static String getShortStringValue(Date val) {
		String stringValue = "";
		if (val == null) {
			return stringValue;
		}
		Date d = (Date) val;
		Calendar cd = Calendar.getInstance();
		cd.setTime(d);
		String year = "" + cd.get(Calendar.YEAR);
		String month = "" + (cd.get(Calendar.MONTH) + 1);
		String day = "" + cd.get(Calendar.DAY_OF_MONTH);

		if (month.length() < 2)
			month = "0" + month;

		if (day.length() < 2)
			day = "0" + day;

		stringValue = day + "/" + month + "/" + year.substring(2, 4);
		return stringValue;
	}
	
	/**
	 * Converts the value object into a String based on the returnType Note:
	 * This is for an xml formatted xsd:date datatype, the formatting must be
	 * YYYY-MM-DD
	 * 
	 * @return
	 */
	public static String getXMLStringValue(Date val) {
		String stringValue = "";
		if (val == null){
			return stringValue;
		}
				Date d = (Date) val;
				Calendar cd = Calendar.getInstance();
				cd.setTime(d);
				String year = "" + cd.get(Calendar.YEAR);
				String month = "" + (cd.get(Calendar.MONTH)+1);
				String day = "" + cd.get(Calendar.DAY_OF_MONTH);

				if (month.length() < 2)
					month = "0" + month;

				if (day.length() < 2)
					day = "0" + day;

				stringValue =  year + "-" + month + "-" + day;
		return stringValue;
	}

	private static Vector tokenize(String values, char c) {
		Vector temp = new Vector();
		int pos = 0;
		int index = values.indexOf(c);
		while(index != -1){
			String tempp = values.substring(pos, index).trim();
			//System.out.println(tempp+pos+index);
			temp.addElement(tempp);
			pos = index+1;
			index = values.indexOf(c,pos);
		}
		temp.addElement(values.substring(pos).trim());
		return temp;
	}
	public static Date getDateFromString(String value) {
		Date result = new Date();
		Vector digits = tokenize(value, '-');

		int day = Integer.valueOf((String)digits.elementAt(2)).intValue();
		int month = Integer.valueOf((String)digits.elementAt(1)).intValue();
		month--;
		int year = Integer.valueOf((String)digits.elementAt(0)).intValue();

		Calendar cd = Calendar.getInstance();
		cd.set(Calendar.DAY_OF_MONTH, day);
		cd.set(Calendar.MONTH, month);
		cd.set(Calendar.YEAR, year);

		result = cd.getTime();

		return result;
	}
	
    public static String[] split(String original, String delimiter) {
        Vector nodes = new Vector();
        // Parse nodes into vector
        int index = original.indexOf(delimiter);
        while(index>=0) {
            nodes.addElement( original.substring(0, index) );
            original = original.substring(index+delimiter.length());
            index = original.indexOf(delimiter);
        }
        // Get the last node
        nodes.addElement( original );
        
        // Create splitted string array
        String[] result = new String[ nodes.size() ];
        if( nodes.size()>0 ) {
            for(int loop=0; loop<nodes.size(); loop++) {
                result[loop] = (String)nodes.elementAt(loop);
            }
            
        }
        return result;
    }	
}
