/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.transport.mailets.redirect;

import java.io.ByteArrayOutputStream;
import java.util.Enumeration;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.james.core.MimeMessageUtil;
import org.apache.james.transport.mailets.utils.MimeMessageUtils;
import org.apache.mailet.Mail;
import org.apache.mailet.base.RFC2822Headers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

public class MailMessageAlteringUtils {

    private static final char LINE_BREAK = '\n';

    public static Builder from(AbstractRedirect mailet) {
        return new Builder(mailet);
    }

    public static class Builder {

        private AbstractRedirect mailet;
        private Mail originalMail;
        private Mail newMail;

        private Builder(AbstractRedirect mailet) {
            this.mailet = mailet;
        }

        public Builder originalMail(Mail originalMail) {
            this.originalMail = originalMail;
            return this;
        }

        public Builder newMail(Mail newMail) {
            this.newMail = newMail;
            return this;
        }

        public void alterNewMessage() throws MessagingException {
            build().alterNewMessage();
        }

        @VisibleForTesting MailMessageAlteringUtils build() {
            Preconditions.checkNotNull(mailet, "'mailet' is mandatory");
            Preconditions.checkNotNull(originalMail, "'originalMail' is mandatory");
            Preconditions.checkNotNull(newMail, "'newMail' is mandatory");
            return new MailMessageAlteringUtils(mailet, originalMail, newMail);
        }
    }

    private final AbstractRedirect mailet;
    private final Mail originalMail;
    private final Mail newMail;

    private MailMessageAlteringUtils(AbstractRedirect mailet, Mail originalMail, Mail newMail) {
        this.mailet = mailet;
        this.originalMail = originalMail;
        this.newMail = newMail;
    }

    /**
     * Builds the message of the newMail in case it has to be altered.
     *
     * @param originalMail the original Mail object
     * @param newMail      the Mail object to build
     */
    private void alterNewMessage() throws MessagingException {

        MimeMessage originalMessage = originalMail.getMessage();
        MimeMessage newMessage = newMail.getMessage();

        // Copy the relevant headers
        copyRelevantHeaders(originalMessage, newMessage);

        String head = new MimeMessageUtils(originalMessage).getMessageHeaders();
        try {
            // Create the message body
            MimeMultipart multipart = new MimeMultipart("mixed");

            // Create the message
            MimeMultipart mpContent = new MimeMultipart("alternative");
            mpContent.addBodyPart(getBodyPart(originalMail, originalMessage, head));

            MimeBodyPart contentPartRoot = new MimeBodyPart();
            contentPartRoot.setContent(mpContent);

            multipart.addBodyPart(contentPartRoot);

            if (mailet.getInitParameters().isDebug()) {
                mailet.log("attachmentType:" + mailet.getInitParameters().getAttachmentType());
            }
            if (!mailet.getInitParameters().getAttachmentType().equals(TypeCode.NONE)) {
                multipart.addBodyPart(getAttachmentPart(originalMail, originalMessage, head));
            }

            if (mailet.getInitParameters().isAttachError() && originalMail.getErrorMessage() != null) {
                multipart.addBodyPart(getErrorPart(originalMail));
            }
            newMail.getMessage().setContent(multipart);
            newMail.getMessage().setHeader(RFC2822Headers.CONTENT_TYPE, multipart.getContentType());

        } catch (Exception ioe) {
            throw new MessagingException("Unable to create multipart body", ioe);
        }
    }

    private BodyPart getBodyPart(Mail originalMail, MimeMessage originalMessage, String head) throws MessagingException, Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(getText(originalMail, originalMessage, head));
        part.setDisposition("inline");
        return part;
    }

    private MimeBodyPart getAttachmentPart(Mail originalMail, MimeMessage originalMessage, String head) throws MessagingException, Exception {
        MimeBodyPart attachmentPart = new MimeBodyPart();
        switch (mailet.getInitParameters().getAttachmentType()) {
            case HEADS:
                attachmentPart.setText(head);
                break;
            case BODY:
                try {
                    attachmentPart.setText(getMessageBody(originalMessage));
                } catch (Exception e) {
                    attachmentPart.setText("body unavailable");
                }
                break;
            case ALL:
                attachmentPart.setText(head + "\r\nMessage:\r\n" + getMessageBody(originalMessage));
                break;
            case MESSAGE:
                attachmentPart.setContent(originalMessage, "message/rfc822");
                break;
            case NONE:
                break;
            case UNALTERED:
                break;
        }
        attachmentPart.setFileName(getFileName(originalMessage.getSubject()));
        attachmentPart.setDisposition("Attachment");
        return attachmentPart;
    }

    @VisibleForTesting String getFileName(String subject) {
        if (subject != null && !subject.trim().isEmpty()) {
            return subject.trim();
        }
        return "No Subject";
    }

    private MimeBodyPart getErrorPart(Mail originalMail) throws MessagingException {
        MimeBodyPart errorPart = new MimeBodyPart();
        errorPart.setContent(originalMail.getErrorMessage(), "text/plain");
        errorPart.setHeader(RFC2822Headers.CONTENT_TYPE, "text/plain");
        errorPart.setFileName("Reasons");
        errorPart.setDisposition(javax.mail.Part.ATTACHMENT);
        return errorPart;
    }

    private String getText(Mail originalMail, MimeMessage originalMessage, String head) throws MessagingException {
        StringBuilder builder = new StringBuilder();

        String messageText = mailet.getMessage(originalMail);
        if (messageText != null) {
            builder.append(messageText)
                .append(LINE_BREAK);
        }

        if (mailet.getInitParameters().isDebug()) {
            mailet.log("inline:" + mailet.getInitParameters().getInLineType());
        }
        switch (mailet.getInitParameters().getInLineType()) {
            case ALL:
                appendHead(builder, head);
                appendBody(builder, originalMessage);
                break;
            case HEADS:
                appendHead(builder, head);
                break;
            case BODY:
                appendBody(builder, originalMessage);
                break;
            case NONE:
                break;
            case MESSAGE:
                break;
            case UNALTERED:
                break;
        }
        return builder.toString();
    }

    private void appendHead(StringBuilder builder, String head) {
        builder.append("Message Headers:")
            .append(LINE_BREAK)
            .append(head)
            .append(LINE_BREAK);
    }

    private void appendBody(StringBuilder builder, MimeMessage originalMessage) {
        builder.append("Message:")
            .append(LINE_BREAK);
        try {
            builder.append(getMessageBody(originalMessage))
                .append(LINE_BREAK);
        } catch (Exception e) {
            builder.append("body unavailable")
                .append(LINE_BREAK);
        }
    }

    /**
     * Utility method for obtaining a string representation of a Message's body
     */
    private String getMessageBody(MimeMessage message) throws Exception {
        ByteArrayOutputStream bodyOs = new ByteArrayOutputStream();
        MimeMessageUtil.writeMessageBodyTo(message, bodyOs);
        return bodyOs.toString();
    }

    private void copyRelevantHeaders(MimeMessage originalMessage, MimeMessage newMessage) throws MessagingException {
        @SuppressWarnings("unchecked")
        Enumeration<String> headerEnum = originalMessage.getMatchingHeaderLines(
                new String[] { RFC2822Headers.DATE, RFC2822Headers.FROM, RFC2822Headers.REPLY_TO, RFC2822Headers.TO, 
                        RFC2822Headers.SUBJECT, RFC2822Headers.RETURN_PATH });
        while (headerEnum.hasMoreElements()) {
            newMessage.addHeaderLine(headerEnum.nextElement());
        }
    }

}
