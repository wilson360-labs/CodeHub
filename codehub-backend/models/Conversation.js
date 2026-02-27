// models/Conversation.js
const mongoose = require('mongoose');

const MessageSchema = new mongoose.Schema({
  role:      { type: String, enum: ['user', 'assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  createdAt: { type: Date, default: Date.now }
});

const ConversationSchema = new mongoose.Schema({
  sessionId:  { type: String, required: true, index: true },
  ip:         { type: String },
  messages:   [MessageSchema],
  totalTokens:{ type: Number, default: 0 },
  createdAt:  { type: Date, default: Date.now },
  updatedAt:  { type: Date, default: Date.now }
});

// Actualizar updatedAt en cada save
ConversationSchema.pre('save', function(next) {
  this.updatedAt = Date.now();
  next();
});

module.exports = mongoose.model('Conversation', ConversationSchema);
