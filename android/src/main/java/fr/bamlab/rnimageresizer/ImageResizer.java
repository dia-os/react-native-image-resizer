package fr.bamlab.rnimageresizer;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Date;

/**
 * Provide methods to resize and rotate an image file.
 */
class ImageResizer {

    private final static String BASE64_PREFIX = "data:image/";

    /**
     * Resize the specified bitmap, keeping its aspect ratio.
     */
    private static Bitmap resizeImage(Bitmap image, int maxWidth, int maxHeight) {
        Bitmap newImage = null;
        if (image == null) {
            return null; // Can't load the image from the given path.
        }

        if (maxHeight > 0 && maxWidth > 0) {
            float width = image.getWidth();
            float height = image.getHeight();

            float ratio = Math.min((float)maxWidth / width, (float)maxHeight / height);

            int finalWidth = (int) (width * ratio);
            int finalHeight = (int) (height * ratio);
            newImage = Bitmap.createScaledBitmap(image, finalWidth, finalHeight, true);
        }

        return newImage;
    }

    /**
     * Rotate the specified bitmap with the given angle, in degrees.
     */
    public static Bitmap rotateImage(Bitmap source, float angle)
    {
        Bitmap retVal;

        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        retVal = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        return retVal;
    }

    /**
     * Save the given bitmap in a directory. Extension is automatically generated using the bitmap format.
     */
    private static String saveImage(Bitmap bitmap, File saveDirectory, String fileName,
                                    Bitmap.CompressFormat compressFormat, int quality)
            throws IOException {
        if (bitmap == null) {
            throw new IOException("The bitmap couldn't be resized");
        }

        File newFile = new File(saveDirectory, fileName + "." + compressFormat.name());
        if(!newFile.createNewFile()) {
            throw new IOException("The file already exists");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(compressFormat, quality, outputStream);
        byte[] bitmapData = outputStream.toByteArray();

        outputStream.flush();
        outputStream.close();

        FileOutputStream fos = new FileOutputStream(newFile);
        fos.write(bitmapData);
        fos.flush();
        fos.close();

        return newFile.getAbsolutePath();
    }

    /**
     * Get {@link File} object for the given Android URI.<br>
     * Use content resolver to get real path if direct path doesn't return valid file.
     */
    private static File getFileFromUri(Context context, Uri uri) {

        // first try by direct path
        File file = new File(uri.getPath());
        if (file.exists()) {
            return file;
        }

        // try reading real path from content resolver (gallery images)
        Cursor cursor = null;
        try {
            String[] proj = {MediaStore.Images.Media.DATA};
            cursor = context.getContentResolver().query(uri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            String realPath = cursor.getString(column_index);
            file = new File(realPath);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return file;
    }


    /**
     * Get orientation by reading Image metadata
     */
    public static int getOrientation(Context context, Uri uri) {
        try {
            File file = getFileFromUri(context, uri);
            if (file.exists()) {
                ExifInterface ei = new ExifInterface(file.getAbsolutePath());
                return getOrientation(ei);
            }
        } catch (Exception ignored) { }

        return 0;
    }

    /**
     * Convert metadata to degrees
     */
    public static int getOrientation(ExifInterface exif) {
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * Compute the inSampleSize value to use to load a bitmap.
     * Adapted from https://developer.android.com/training/displaying-bitmaps/load-bitmap.html
     */
    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;

        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * Load a bitmap either from a real file or using the {@link ContentResolver} of the current
     * {@link Context} (to read gallery images for example).
     */
    private static Bitmap loadBitmap(Context context, String imagePath, BitmapFactory.Options options) throws IOException {
        Bitmap sourceImage = null;
        if (!imagePath.startsWith("content://") && !imagePath.startsWith("file://")) {
            sourceImage = BitmapFactory.decodeFile(imagePath, options);
        } else {
            ContentResolver cr = context.getContentResolver();
            InputStream input = cr.openInputStream(Uri.parse(imagePath));
            if (input != null) {
                sourceImage = BitmapFactory.decodeStream(input, null, options);
                input.close();
            }
        }
        return sourceImage;
    }

    /**
     * Loads the bitmap resource from the file specified in imagePath.
     */
    private static Bitmap loadBitmapFromFile(Context context, String imagePath, int newWidth,
                                             int newHeight) throws IOException  {
        // Decode the image bounds to find the size of the source image.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        loadBitmap(context, imagePath, options);

        // Set a sample size according to the image size to lower memory usage.
        options.inSampleSize = calculateInSampleSize(options, newWidth, newHeight);
        options.inJustDecodeBounds = false;
        System.out.println(options.inSampleSize);
        return loadBitmap(context, imagePath, options);

    }

    /**
     * Loads the bitmap resource from a base64 encoded jpg or png.
     * Format is as such:
     * png: 'data:image/png;base64,iVBORw0KGgoAA...'
     * jpg: 'data:image/jpeg;base64,/9j/4AAQSkZJ...'
     */
    private static Bitmap loadBitmapFromBase64(String imagePath) {
        Bitmap sourceImage = null;
        Log.d("resize1110a", "1");
        // base64 image.  Convert to a bitmap.
        final int prefixLen = BASE64_PREFIX.length();
        final boolean isJpeg = (imagePath.indexOf("jpeg") == prefixLen);
        final boolean isPng = (!isJpeg) && (imagePath.indexOf("png") == prefixLen);
        int commaLocation = -1;
        if (isJpeg || isPng){
            commaLocation = imagePath.indexOf(',');
        }

            if (commaLocation > 0) {
                //Log.d("resize1110b", "1");
                try {
                //Log.d("resize1110c", "x");
                final String encodedImage = imagePath.substring(commaLocation+1);
                //Log.d("resize1110c", "1");
                final byte[] decodedString = Base64.decode(encodedImage, Base64.DEFAULT);
                //Log.d("resize1111", "1");
                sourceImage = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                //Log.d("resize1112", "1");
                }catch (OutOfMemoryError e){
           /* try {
                Log.d("resize111", e.toString());
            }catch (Exception exp){
                Log.d("resize11100", e.toString());
            }*/
                    return null;
                }
            }

        //Log.d("checkpoint12","2");

        return sourceImage;
    }

    /**
     * Create a resized version of the given image.
     */
    public static String createResizedImage(Context context, String imagePath, int newWidth,
                                            int newHeight, Bitmap.CompressFormat compressFormat,
                                            int quality, int rotation, String outputPath)  {
        Bitmap sourceImage = null;

        try{
            // If the BASE64_PREFIX is absent, load bitmap from a file.  Otherwise, load from base64.
            if (imagePath.indexOf(BASE64_PREFIX) < 0) {
                //Log.d("checkpointaaaa","1");
                sourceImage = ImageResizer.loadBitmapFromFile(context, imagePath, newWidth, newHeight);
                //Log.d("checkpointbbbb","1");
            }
            else {
                //Log.d("checkpoint000","1");
                sourceImage = ImageResizer.loadBitmapFromBase64(imagePath);
                //Log.d("checkpoint111",sourceImage.toString());
            }
            //Log.d("checkpoint1",sourceImage.toString());

            if (sourceImage == null){
                //Log.d("checkpoint2","1");
                return "";
            }

            //Log.d("checkpoint3","1");
            // Scale it first so there are fewer pixels to transform in the rotation
            Bitmap scaledImage = ImageResizer.resizeImage(sourceImage, newWidth, newHeight);
            if (sourceImage != scaledImage) {
                sourceImage.recycle();
            }
            //Log.d("checkpoint4","1");
            // Rotate if necessary
            Bitmap rotatedImage = scaledImage;
            int orientation = getOrientation(context, Uri.parse(imagePath));
            rotation = orientation + rotation;
            rotatedImage = ImageResizer.rotateImage(scaledImage, rotation);

            if (scaledImage != rotatedImage) {
                scaledImage.recycle();
            }

            //Log.d("checkpoint5","1");
            // Save the resulting image
            File path = context.getCacheDir();
            if (outputPath != null) {
                path = new File(outputPath);
            }
            //Log.d("checkpoint6","1");
            String resizedImagePath = ImageResizer.saveImage(rotatedImage, path,
                    Long.toString(new Date().getTime()), compressFormat, quality);

            // Clean up remaining image
            rotatedImage.recycle();
            //Log.d("checkpoint7","1");
            return resizedImagePath;

        }catch (OutOfMemoryError e){
             //Log.d("resizedImagePath111",e.toString());
            return "";
        }catch (Exception e){
            return "";
        }



    }
}
