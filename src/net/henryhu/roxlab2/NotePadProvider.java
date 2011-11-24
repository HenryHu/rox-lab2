/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.henryhu.roxlab2;


import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.ContentProvider.PipeDataWriter;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.LiveFolders;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import net.henryhu.roxlab2.NotePad;

/**
 * Provides access to a database of notes. Each note has a title, the note
 * itself, a creation date and a modified data.
 */
public class NotePadProvider extends ContentProvider implements PipeDataWriter<Cursor> {
	private static AmazonS3 s3;
	private static Bucket bucket = null;
    // Used for debugging and logging
    private static final String TAG = "NotePadProvider";

    private static final String bucketName = "rox-lab2";
    
    /**
     * A projection map used to select columns from the database
     */
    private static HashMap<String, String> sNotesProjectionMap;

    /**
     * A projection map used to select columns from the database
     */
    private static HashMap<String, String> sLiveFolderProjectionMap;

    /**
     * Standard projection for the interesting columns of a normal note.
     */
    private static final String[] READ_NOTE_PROJECTION = new String[] {
            NotePad.Notes._ID,               // Projection position 0, the note's id
            NotePad.Notes.COLUMN_NAME_NOTE,  // Projection position 1, the note's content
            NotePad.Notes.COLUMN_NAME_TITLE, // Projection position 2, the note's title
    };
    private static final int READ_NOTE_NOTE_INDEX = 1;
    private static final int READ_NOTE_TITLE_INDEX = 2;

    /*
     * Constants used by the Uri matcher to choose an action based on the pattern
     * of the incoming URI
     */
    // The incoming URI matches the Notes URI pattern
    private static final int NOTES = 1;

    // The incoming URI matches the Note ID URI pattern
    private static final int NOTE_ID = 2;

    // The incoming URI matches the Live Folder URI pattern
    private static final int LIVE_FOLDER_NOTES = 3;

    /**
     * A UriMatcher instance
     */
    private static final UriMatcher sUriMatcher;


    /**
     * A block that instantiates and sets static objects
     */
    static {

        /*
         * Creates and initializes the URI matcher
         */
        // Create a new instance
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

        // Add a pattern that routes URIs terminated with "notes" to a NOTES operation
        sUriMatcher.addURI(NotePad.AUTHORITY, "notes", NOTES);

        // Add a pattern that routes URIs terminated with "notes" plus an integer
        // to a note ID operation
        sUriMatcher.addURI(NotePad.AUTHORITY, "notes/*", NOTE_ID);

        // Add a pattern that routes URIs terminated with live_folders/notes to a
        // live folder operation
        sUriMatcher.addURI(NotePad.AUTHORITY, "live_folders/notes", LIVE_FOLDER_NOTES);

        /*
         * Creates and initializes a projection map that returns all columns
         */

        // Creates a new projection map instance. The map returns a column name
        // given a string. The two are usually equal.
        sNotesProjectionMap = new HashMap<String, String>();

        // Maps the string "_ID" to the column name "_ID"
        sNotesProjectionMap.put(NotePad.Notes._ID, NotePad.Notes._ID);

        // Maps "title" to "title"
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_TITLE);

        // Maps "note" to "note"
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_NOTE, NotePad.Notes.COLUMN_NAME_NOTE);

        // Maps "created" to "created"
        sNotesProjectionMap.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE,
                NotePad.Notes.COLUMN_NAME_CREATE_DATE);

        // Maps "modified" to "modified"
        sNotesProjectionMap.put(
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE);

        /*
         * Creates an initializes a projection map for handling Live Folders
         */

        // Creates a new projection map instance
        sLiveFolderProjectionMap = new HashMap<String, String>();

        // Maps "_ID" to "_ID AS _ID" for a live folder
        sLiveFolderProjectionMap.put(LiveFolders._ID, NotePad.Notes._ID + " AS " + LiveFolders._ID);

        // Maps "NAME" to "title AS NAME"
        sLiveFolderProjectionMap.put(LiveFolders.NAME, NotePad.Notes.COLUMN_NAME_TITLE + " AS " +
            LiveFolders.NAME);
    }

   @Override
   public boolean onCreate()
   {
	   s3 = new AmazonS3Client(new PropertiesCredentials(
			NotePadProvider.class.getResourceAsStream("AwsCredentials.properties")));
	   

	   return true;
   }

   /**
    * This method is called when a client calls
    * {@link android.content.ContentResolver#query(Uri, String[], String, String[], String)}.
    * Queries the database and returns a cursor containing the results.
    *
    * @return A cursor containing the results of the query. The cursor exists but is empty if
    * the query returns no results or an exception occurs.
    * @throws IllegalArgumentException if the incoming URI pattern is invalid.
    */
   @Override
   public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
           String sortOrder) {

	   // ignore selection and selectionArgs and sortOrder
	   
	   HashMap<String, String> pmap;
	   String noteId = "";
       /**
        * Choose the projection and adjust the "where" clause based on URI pattern-matching.
        */
       switch (sUriMatcher.match(uri)) {
           // If the incoming URI is for notes, chooses the Notes projection
           case NOTES:
               pmap = sNotesProjectionMap;
               break;

           /* If the incoming URI is for a single note identified by its ID, chooses the
            * note ID projection, and appends "_ID = <noteID>" to the where clause, so that
            * it selects that single note
            */
           case NOTE_ID:
               pmap = sNotesProjectionMap;
               noteId = uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION);
               break;

           case LIVE_FOLDER_NOTES:
               // If the incoming URI is from a live folder, chooses the live folder projection.
               pmap = sLiveFolderProjectionMap;
               break;

           default:
               // If the URI doesn't match any of the known patterns, throw an exception.
               throw new IllegalArgumentException("Unknown URI " + uri);
       }

       MatrixCursor c = new MatrixCursor(projection);
       if (noteId.equals(""))
       {
    	   List<ContentValues> items = s3list();
           for (ContentValues vals : items)
               c.addRow(contentValuesToRow(vals, projection, pmap));
       }
       else
       {
           Log.w("query()", "ID to query: " + noteId);
    	   ContentValues vals = s3query(noteId);
    	   if (vals == null)
    	   {
    		   Log.w("query()", "query failed: " + noteId);
    	   } else {
               Log.w("query()", "query successed: " + noteId);
               c.addRow(contentValuesToRow(vals, projection, pmap));
           }
       }

       Log.w("query()", "notification URI: " + uri);
       // Tells the Cursor what URI to watch, so it knows when its source data changes
       c.setNotificationUri(getContext().getContentResolver(), uri);
       return c;
   }

   /**
    * This is called when a client calls {@link android.content.ContentResolver#getType(Uri)}.
    * Returns the MIME data type of the URI given as a parameter.
    *
    * @param uri The URI whose MIME type is desired.
    * @return The MIME type of the URI.
    * @throws IllegalArgumentException if the incoming URI pattern is invalid.
    */
   @Override
   public String getType(Uri uri) {

       /**
        * Chooses the MIME type based on the incoming URI pattern
        */
       switch (sUriMatcher.match(uri)) {

           // If the pattern is for notes or live folders, returns the general content type.
           case NOTES:
           case LIVE_FOLDER_NOTES:
               return NotePad.Notes.CONTENT_TYPE;

           // If the pattern is for note IDs, returns the note ID content type.
           case NOTE_ID:
               return NotePad.Notes.CONTENT_ITEM_TYPE;

           // If the URI pattern doesn't match any permitted patterns, throws an exception.
           default:
               throw new IllegalArgumentException("Unknown URI " + uri);
       }
    }


    /**
     * This describes the MIME types that are supported for opening a note
     * URI as a stream.
     */
    static ClipDescription NOTE_STREAM_TYPES = new ClipDescription(null,
            new String[] { ClipDescription.MIMETYPE_TEXT_PLAIN });

    /**
     * Returns the types of available data streams.  URIs to specific notes are supported.
     * The application can convert such a note to a plain text stream.
     *
     * @param uri the URI to analyze
     * @param mimeTypeFilter The MIME type to check for. This method only returns a data stream
     * type for MIME types that match the filter. Currently, only text/plain MIME types match.
     * @return a data stream MIME type. Currently, only text/plan is returned.
     * @throws IllegalArgumentException if the URI pattern doesn't match any supported patterns.
     */
    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        /**
         *  Chooses the data stream type based on the incoming URI pattern.
         */
        switch (sUriMatcher.match(uri)) {

            // If the pattern is for notes or live folders, return null. Data streams are not
            // supported for this type of URI.
            case NOTES:
            case LIVE_FOLDER_NOTES:
                return null;

            // If the pattern is for note IDs and the MIME filter is text/plain, then return
            // text/plain
            case NOTE_ID:
                return NOTE_STREAM_TYPES.filterMimeTypes(mimeTypeFilter);

                // If the URI pattern doesn't match any permitted patterns, throws an exception.
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
            }
    }


    /**
     * Returns a stream of data for each supported stream type. This method does a query on the
     * incoming URI, then uses
     * {@link android.content.ContentProvider#openPipeHelper(Uri, String, Bundle, Object,
     * PipeDataWriter)} to start another thread in which to convert the data into a stream.
     *
     * @param uri The URI pattern that points to the data stream
     * @param mimeTypeFilter A String containing a MIME type. This method tries to get a stream of
     * data with this MIME type.
     * @param opts Additional options supplied by the caller.  Can be interpreted as
     * desired by the content provider.
     * @return AssetFileDescriptor A handle to the file.
     * @throws FileNotFoundException if there is no file associated with the incoming URI.
     */
    @Override
    public synchronized AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {

        // Checks to see if the MIME type filter matches a supported MIME type.
        String[] mimeTypes = getStreamTypes(uri, mimeTypeFilter);

        // If the MIME type is supported
        if (mimeTypes != null) {

            // Retrieves the note for this URI. Uses the query method defined for this provider,
            // rather than using the database query method.
            Cursor c = query(
                    uri,                    // The URI of a note
                    READ_NOTE_PROJECTION,   // Gets a projection containing the note's ID, title,
                                            // and contents
                    null,                   // No WHERE clause, get all matching records
                    null,                   // Since there is no WHERE clause, no selection criteria
                    null                    // Use the default sort order (modification date,
                                            // descending
            );


            // If the query fails or the cursor is empty, stop
            if (c == null || !c.moveToFirst()) {

                // If the cursor is empty, simply close the cursor and return
                if (c != null) {
                    c.close();
                }

                // If the cursor is null, throw an exception
                throw new FileNotFoundException("Unable to query " + uri);
            }

            // Start a new thread that pipes the stream data back to the caller.
            return new AssetFileDescriptor(
                    openPipeHelper(uri, mimeTypes[0], opts, c, this), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }

        // If the MIME type is not supported, return a read-only handle to the file.
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts);
    }

    /**
     * Implementation of {@link android.content.ContentProvider.PipeDataWriter}
     * to perform the actual work of converting the data in one of cursors to a
     * stream of data for the client to read.
     */
    @Override
    public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
            Bundle opts, Cursor c) {
        // We currently only support conversion-to-text from a single note entry,
        // so no need for cursor data type checking here.
        FileOutputStream fout = new FileOutputStream(output.getFileDescriptor());
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new OutputStreamWriter(fout, "UTF-8"));
            pw.println(c.getString(READ_NOTE_TITLE_INDEX));
            pw.println("");
            pw.println(c.getString(READ_NOTE_NOTE_INDEX));
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, "Ooops", e);
        } finally {
            c.close();
            if (pw != null) {
                pw.flush();
            }
            try {
                fout.close();
            } catch (IOException e) {
            }
        }
    }
    
    public String contentValuesToString(ContentValues values)
    {
    	HashMap<String, String> entries = new HashMap<String, String>();
    	for (String key : values.keySet())
    	{
    		String val = values.getAsString(key);
    		entries.put(key, val);
    	}
    	return ParseInfo.transMap(entries);
    }
    
    public String objectKeyToId(String key)
    {
    	return key.substring(0, key.indexOf("_"));
    }
    
    public String objectKeyToTitle(String key)
    {
    	return key.substring(key.indexOf("_") + 1);
    }
    
    private List<ContentValues> s3list()
    {
    	if (bucket == null)
    		bucket = s3.createBucket(bucketName);
        List<ContentValues> items = new ArrayList<ContentValues>();
    	ObjectListing ol = s3.listObjects(bucketName);
    	for (Object o : ol.getObjectSummaries())
    	{
            ContentValues ret = new ContentValues();
    		String key = ((S3ObjectSummary)o).getKey();
    		String id = objectKeyToId(key);
            String title = objectKeyToTitle(key);
    		ret.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
            ret.put(NotePad.Notes._ID, id);
    		Log.w("s3list()", "id: " + id + " title: " + title);
            items.add(ret);
    	}
    	Log.w("s3list()", "total id: " + items.size());
    	return items;
    }

    private int s3put(String key, ContentValues values)
    {
    	Log.w("s3put()", "put with key: " + key);
		String sValues = contentValuesToString(values);
		
		byte[] bVals = null;
		try {
			bVals = sValues.getBytes("UTF-8");
		} catch (Exception e) {}
		InputStream is = new ByteArrayInputStream(bVals);
		ObjectMetadata om = new ObjectMetadata();
		om.setContentLength(bVals.length);
		om.setContentType("text/plain");
		
		try {
			s3.putObject(bucketName, key, is, om);
			return 1;
		} catch (AmazonClientException e)
		{
			Log.w("s3put()", "Exception: " + e.toString() + " ; " + e.getMessage());
			
			return 0;
		}
    }
    
    private int s3insert(String id, ContentValues values)
    {
    	values.put(NotePad.Notes._ID, id);
		String key = id + "_" + values.getAsString(NotePad.Notes.COLUMN_NAME_TITLE);
    	return s3put(key, values);
    }
    
    private String s3insert(ContentValues initialValues)
    {
    	if (bucket == null)
    		bucket = s3.createBucket(bucketName);
    	String id = String.valueOf(UUID.randomUUID().getLeastSignificantBits() & 0x7fffffffffffffffL);
    	int ret = s3insert(id, initialValues);
    	Log.w("s3insert()", "inserting with id: " + id + " ret: " + ret);
    	
    	return id;
    }
    
    private int s3delete(String id)
    {
    	if (bucket == null)
    		bucket = s3.createBucket(bucketName);
    	int count = 0;
    	ObjectListing ol = s3.listObjects(bucketName, id + "_");
    	for (Object o : ol.getObjectSummaries())
    	{
    		S3ObjectSummary sum = (S3ObjectSummary)o;
    		s3.deleteObject(bucketName, sum.getKey());
    		count++;
    	}
    	Log.w("s3delete()", "delete with id: " + id + " count: " + count);
    	return count;
    }
    
    private int s3update(String id, ContentValues values)
    {
    	if (bucket == null)
    		bucket = s3.createBucket(bucketName);
    	
    	values.put(NotePad.Notes._ID, id);
    	Log.w("s3update()", "update: " + id);
    	ContentValues oldvalues = s3query(id);
    	if (oldvalues == null)
    		return 0;
        Log.w("s3update()", "before delete");
    	s3delete(id);
    	for (String key : oldvalues.keySet())
    	{
    		if (values.getAsBoolean(key) == null)
    		{
    			values.put(key, oldvalues.getAsString(key));
    		}
    	}
        Log.w("s3update()", "before insert");
    	return s3insert(id, values);
    }
    
    private ContentValues s3query(String id)
    {
    	if (bucket == null)
    		bucket = s3.createBucket(bucketName);
    	
    	Log.w("s3query()", "query id: " + id);

    	ObjectListing ol = s3.listObjects(bucketName, id + "_");
    	for (Object o : ol.getObjectSummaries())
    	{
    		S3ObjectSummary sum = (S3ObjectSummary)o;
    		S3Object obj = s3.getObject(bucketName, sum.getKey());
    		
    		ObjectMetadata om = obj.getObjectMetadata();
    		
    		byte[] buf = new byte[(int)om.getContentLength()];
    		try {
    			InputStream contents = obj.getObjectContent();
    			contents.read(buf);
    			contents.close();
    			String sbuf = new String(buf, "UTF-8");
    			Map<String, String> entries = ParseInfo.getEntries(sbuf);
    			ContentValues vals = new ContentValues();
    			for (String key : entries.keySet())
    			{
    				vals.put(key, entries.get(key));
    			}
    	    	Log.w("s3query()", "query succ");
    			return vals;
    		}
    		catch (Exception e) {}
    	}
    	Log.w("s3query()", "query fail");
    	return null;
    }
    
    public Object[] contentValuesToRow(ContentValues vals, String[] projection, Map<String, String> pmap)
    {
    	Object[] result = new Object[projection.length];
    	int cnt = 0;
    	
    	for (String col : projection)
    	{
//    		Log.w("contentValuesToRow()", "add " + col + " -> " + vals.get(col));
    		result[cnt] = vals.get(col); // what is pmap, then?
    		cnt++;
    	}
    	return result;
    }

    /**
     * This is called when a client calls
     * {@link android.content.ContentResolver#insert(Uri, ContentValues)}.
     * Inserts a new row into the database. This method sets up default values for any
     * columns that are not included in the incoming map.
     * If rows were inserted, then listeners are notified of the change.
     * @return The row ID of the inserted row.
     * @throws SQLException if the insertion fails.
     */
    @Override
    public synchronized Uri insert(Uri uri, ContentValues initialValues) {

        // Validates the incoming URI. Only the full provider URI is allowed for inserts.
        if (sUriMatcher.match(uri) != NOTES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // A map to hold the new record's values.
        ContentValues values;

        // If the incoming values map is not null, uses it for the new values.
        if (initialValues != null) {
            values = new ContentValues(initialValues);

        } else {
            // Otherwise, create a new value map
            values = new ContentValues();
        }

        // Gets the current system time in milliseconds
        String now = Long.valueOf(System.currentTimeMillis()).toString();

        // If the values map doesn't contain the creation date, sets the value to the current time.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_CREATE_DATE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, now);
        }

        // If the values map doesn't contain the modification date, sets the value to the current
        // time.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, now);
        }

        // If the values map doesn't contain a title, sets the value to the default title.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_TITLE) == false) {
            Resources r = Resources.getSystem();
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, r.getString(android.R.string.untitled));
        }

        // If the values map doesn't contain note text, sets the value to an empty string.
        if (values.containsKey(NotePad.Notes.COLUMN_NAME_NOTE) == false) {
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, "");
        }

        // Performs the insert and returns the ID of the new note.
        String id = s3insert(values);

        // If the insert succeeded, the row ID exists.
        if (id != null) {
            // Creates a URI with the note ID pattern and the new row ID appended to it.
            Uri noteUri = Uri.withAppendedPath(NotePad.Notes.CONTENT_ID_URI_BASE, id);

            Log.w("insert()", "notify change: " + noteUri);
            // Notifies observers registered against this provider that the data changed.
            getContext().getContentResolver().notifyChange(noteUri, null);
            return noteUri;
        }

        // If the insert didn't succeed, then the rowID is <= 0. Throws an exception.
        throw new SQLException("Failed to insert row into " + uri);
    }

    /**
     * This is called when a client calls
     * {@link android.content.ContentResolver#delete(Uri, String, String[])}.
     * Deletes records from the database. If the incoming URI matches the note ID URI pattern,
     * this method deletes the one record specified by the ID in the URI. Otherwise, it deletes a
     * a set of records. The record or records must also match the input selection criteria
     * specified by where and whereArgs.
     *
     * If rows were deleted, then listeners are notified of the change.
     * @return If a "where" clause is used, the number of rows affected is returned, otherwise
     * 0 is returned. To delete all rows and get a row count, use "1" as the where clause.
     * @throws IllegalArgumentException if the incoming URI pattern is invalid.
     */
    @Override
    public synchronized int delete(Uri uri, String where, String[] whereArgs) {
    	// we ignore where and whereArgs for now

        int count = 0;

        // Does the delete based on the incoming URI pattern.
        switch (sUriMatcher.match(uri)) {

            // If the incoming pattern matches the general pattern for notes, does a delete
            // based on the incoming "where" columns and arguments.
            case NOTES:
                break;

                // If the incoming URI matches a single note ID, does the delete based on the
                // incoming data, but modifies the where clause to restrict it to the
                // particular note ID.
            case NOTE_ID:
                /*
                 * Starts a final WHERE clause by restricting it to the
                 * desired note ID.
                 */
            	count = s3delete(uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION));
                break;

            // If the incoming pattern is invalid, throws an exception.
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        Log.w("delete()", "notify change: " + uri);
        /*Gets a handle to the content resolver object for the current context, and notifies it
         * that the incoming URI changed. The object passes this along to the resolver framework,
         * and observers that have registered themselves for the provider are notified.
         */
        getContext().getContentResolver().notifyChange(uri, null);

        // Returns the number of rows deleted.
        return count;
    }

    /**
     * This is called when a client calls
     * {@link android.content.ContentResolver#update(Uri,ContentValues,String,String[])}
     * Updates records in the database. The column names specified by the keys in the values map
     * are updated with new data specified by the values in the map. If the incoming URI matches the
     * note ID URI pattern, then the method updates the one record specified by the ID in the URI;
     * otherwise, it updates a set of records. The record or records must match the input
     * selection criteria specified by where and whereArgs.
     * If rows were updated, then listeners are notified of the change.
     *
     * @param uri The URI pattern to match and update.
     * @param values A map of column names (keys) and new values (values).
     * @param where An SQL "WHERE" clause that selects records based on their column values. If this
     * is null, then all records that match the URI pattern are selected.
     * @param whereArgs An array of selection criteria. If the "where" param contains value
     * placeholders ("?"), then each placeholder is replaced by the corresponding element in the
     * array.
     * @return The number of rows updated.
     * @throws IllegalArgumentException if the incoming URI pattern is invalid.
     */
    @Override
    public synchronized int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
    	// we ignore where and whereArgs for now

        int count = 0;
        

        // Does the update based on the incoming URI pattern
        switch (sUriMatcher.match(uri)) {

            // If the incoming URI matches the general notes pattern, does the update based on
            // the incoming data.
            case NOTES:

                break;

            // If the incoming URI matches a single note ID, does the update based on the incoming
            // data, but modifies the where clause to restrict it to the particular note ID.
            case NOTE_ID:
                // From the incoming URI, get the note ID
                String noteId = uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION);
                
                count = s3update(noteId, values);

                break;
            // If the incoming pattern is invalid, throws an exception.
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        /*Gets a handle to the content resolver object for the current context, and notifies it
         * that the incoming URI changed. The object passes this along to the resolver framework,
         * and observers that have registered themselves for the provider are notified.
         */
        Log.w("update()", "notify change: " + uri);
        getContext().getContentResolver().notifyChange(uri, null);

        // Returns the number of rows updated.
        return count;
    }

}
