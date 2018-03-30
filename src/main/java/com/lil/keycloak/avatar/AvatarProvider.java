package com.lil.keycloak.avatar;

import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.policy.PolicyType;
import org.apache.commons.io.FilenameUtils;
import org.imgscalr.Scalr;
import org.jboss.logging.Logger;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.xmlpull.v1.XmlPullParserException;
import sun.awt.image.ImageFormatException;

import javax.imageio.ImageIO;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static java.util.Arrays.asList;

public class AvatarProvider implements RealmResourceProvider {

    protected static final Logger logger = Logger.getLogger(AvatarProvider.class);

    private KeycloakSession session;

    private String[] allowedTypes = {
        "jpg", "jpeg", "png", "bmp"
    };

    public AvatarProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public AvatarResult upload(@Context final UriInfo uriInfo, MultipartFormDataInput input)  throws IOException {

        AvatarResult out = new AvatarResult();

        AuthenticationManager.AuthResult authResult = AuthenticationManager.authenticateIdentityCookie(session,
                session.getContext().getRealm(), true);

        if (authResult == null) {
            out.setError("not_auth");
            out.setStatus("error");
            return out;
        } else {
            try {

                UserModel user = authResult.getUser();

                MinioClient minioClient = new MinioClient(System.getenv("MINIO_URL"), System.getenv("MINIO_ACCESS_KEY"), System.getenv("MINIO_SECRET_KEY"));

                String bucket = "avatars";

                if (!minioClient.bucketExists(bucket)) {
                    minioClient.makeBucket(bucket);
                }

                Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
                InputPart avatar = uploadForm.get("avatar").get(0);
                InputStream avatarInputStreem = input.getFormDataPart("avatar", InputStream.class, null);
//                validateImage(avatarInputStreem);

                MultivaluedMap<String, String> header = avatar.getHeaders();
                String fileName = getFileName(header);
                String origName = FilenameUtils.removeExtension(fileName);
                String origExt = FilenameUtils.getExtension(fileName);

                if (!Arrays.asList(allowedTypes).contains(origExt)) {
                    throw new ImageFormatException("NOT_VALID_IMG");
                }

                File original = saveFile(avatarInputStreem, user.getId() + "." + origExt);

                //save original to minio
                minioClient.putObject(bucket,
                        File.separator + user.getId() + File.separator + original.getName(),
                        original.getAbsolutePath()
                        );

                File thumb = createThumb(original, 150, 150);
                minioClient.putObject(bucket,
                        File.separator + user.getId() + File.separator + thumb.getName(),
                        thumb.getAbsolutePath()
                );

                File medium = createThumb(original, 350, 350);
                minioClient.putObject(bucket,
                        File.separator + user.getId() + File.separator + medium.getName(),
                        medium.getAbsolutePath()
                );

                File large = createThumb(original, 600, 600);
                minioClient.putObject(bucket,
                        File.separator + user.getId() + File.separator + large.getName(),
                        large.getAbsolutePath()
                );

                String avatarUrl = System.getenv("MINIO_URL") + "/" + bucket + "/"
                        + user.getId() + "/" + user.getId() + "%s" + "." + origExt + "?v=" + Math.abs((new Random()).nextInt());

                user.setAttribute("avatar", asList(avatarUrl));

                original.delete();
                thumb.delete();
                medium.delete();
                large.delete();
                minioClient.setBucketPolicy(bucket, user.getId(), PolicyType.READ_ONLY);
                out.setStatus("success");
                out.setAvatar(avatarUrl);
                return out;

            } catch (MinioException e) {
                e.printStackTrace();
                out.setError("can not upload to file server");
                out.setStatus("error");
                return out;
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                out.setError("err2");
                out.setStatus("error");
                return out;
            } catch (XmlPullParserException e) {
                e.printStackTrace();
                out.setError("err3");
                out.setStatus("error");
                return out;
            } catch (InvalidKeyException e) {
                e.printStackTrace();
                out.setError("err4");
                out.setStatus("error");
            } catch (IOException e) {
                e.printStackTrace();
                out.setError("err5");
                out.setStatus("error");
                return out;
            } catch (ImageFormatException e) {
                out.setError(e.getMessage());
                out.setStatus("error");
                return out;
            }
        }

        return out;
    }

    private File saveFile(InputStream is, String fileName) throws IOException {
        fileName = System.getProperty("jboss.server.temp.dir") + File.separator + fileName;

        File file = new File(fileName);
        if (!file.exists()) {
            file.createNewFile();
        }

        OutputStream outpuStream = new FileOutputStream(file);
        int read = 0;
        byte[] bytes = new byte[1024];
        while ((read = is.read(bytes)) != -1) {
            outpuStream.write(bytes, 0, read);
        }
        outpuStream.flush();
        outpuStream.close();
        return  file;
    }

    private File createThumb(File file, int w, int h) throws IOException {
        String name = FilenameUtils.removeExtension(file.getName());
        String ext = FilenameUtils.getExtension(file.getName());
        String saveFileName = System.getProperty("jboss.server.temp.dir")
                + File.separator + name + "_" +  w + "_" + h + "." + ext;
        logger.info(file);

        BufferedImage img = ImageIO.read(file);
        logger.info(img);
        BufferedImage resizedImg = Scalr.resize(img, Scalr.Method.SPEED,
                Scalr.Mode.FIT_TO_WIDTH, w, h);

        File result = new File(saveFileName);
        ImageIO.write(resizedImg, ext, result);

        return result;
    }

    private String getFileName(MultivaluedMap<String, String> header) {

        String[] contentDisposition = header.getFirst("Content-Disposition").split(";");

        for (String filename : contentDisposition) {
            if ((filename.trim().startsWith("filename"))) {

                String[] name = filename.split("=");

                return name[1].trim().replaceAll("\"", "");
            }
        }
        return "unknown";
    }



    @Override
    public void close() {
    }
}
