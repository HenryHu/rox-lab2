package net.henryhu.roxlab2;

import java.util.HashMap;
import java.util.Map;

public class ParseInfo {
	public static String transString(String str)
	{
		if (str == null)
			return "(null)";
		StringBuilder sb = new StringBuilder();
		for (int i=0; i<str.length(); i++)
		{
			if (str.charAt(i) == '\\' || str.charAt(i) == ',' || str.charAt(i) == ':')
			{
				sb.append('\\');
				sb.append(str.charAt(i));
			} else {
				sb.append(str.charAt(i));
			}
		}
		return sb.toString();
	}
	
	public static String transMap(Map<String, String> input)
	{
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String> entry : input.entrySet())
		{
			if (!first)
				sb.append(",");
			first = false;
			sb.append(transString(entry.getKey()));
			sb.append(":");
			sb.append(transString(entry.getValue()));
		}
		return sb.toString();
	}
	
	public static Map<String, String> getEntries(String message) throws Exception
	{
		Map<String, String> result = new HashMap<String, String>();
		
		char lxch = 0;
		int last_start = 0;
		
		for (int j=0; j<message.length(); j++)
		{
			if (message.charAt(j) == ',' && lxch != '\\')
			{
				Entry<String, String> entry = parseEntry(message.substring(last_start, j));
				result.put(entry.getKey(), entry.getValue());
				last_start = j + 1;
			}
			lxch = message.charAt(j);
		}
		
		if (last_start != message.length() - 1)
		{
			Entry<String, String> entry = parseEntry(message.substring(last_start));
			result.put(entry.getKey(), entry.getValue());			
		}
		
		return result;
	}
	
	public static String parseString(String str)
	{
		StringBuilder sb = new StringBuilder();
		boolean in_trans = false;
		for (int i=0; i<str.length(); i++)
		{
			if (str.charAt(i) == '\\')
			{
				if (in_trans)
				{
					sb.append('\\');
					in_trans = false;
				}
				else
					in_trans = true;
			} else {
				sb.append(str.charAt(i));
				in_trans = false;
			}
		}
		return sb.toString();
	}
	
	public static Entry<String, String> parseEntry(String pair) throws Exception
	{
		char lch = 0;
		int midpt = -1;
		for (int i=0; i<pair.length(); i++)
		{
			if (pair.charAt(i) == ':' && lch != '\\')
			{
				midpt = i;
				break;
			}
			lch = pair.charAt(i);
		}
		
		if (midpt == -1)
			throw new Exception("Format error");
		
		return new Entry<String, String>(parseString(pair.substring(0, midpt)), parseString(pair.substring(midpt + 1)));
	}
}
