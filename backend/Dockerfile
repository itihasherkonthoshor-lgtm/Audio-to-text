FROM node:22-slim

# Install ffmpeg so server-side whole-file splitting works alongside client-side chunking
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY jobStore.js server.js ./

ENV NODE_ENV=production
ENV PORT=8080
ENV JOB_DATA_DIR=/tmp/audiotext-jobs

EXPOSE 8080

CMD ["node", "server.js"]
