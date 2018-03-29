package com.lil.keycloak.avatar;

public class AvatarResult {

    protected String status;
    protected String error;
    protected String avatar;

    public AvatarResult() {

    }

    public  String getStatus() {
        return  status;
    }

    public  String getError() {
        return  error;
    }

    public  String getAvatar() {
        return  avatar;
    }

    public void setStatus(String st) {
        status = st;
    }

    public void setAvatar(String av) {
        avatar = av;
    }

    public void setError(String er) {
        error = er;
    }

}
